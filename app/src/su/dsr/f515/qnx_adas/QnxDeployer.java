package su.dsr.f515.qnx_adas;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Installs a verified binary/model pair without overwriting the previous release. */
final class QnxDeployer {
    private static final String NFS = "/mnt/qnxshare/var/log";
    private final Context context;
    private final QnxControl control;
    private final String baseDirectory;

    QnxDeployer(Context context, QnxControl control) {
        this(context, control, "/var/log/fcw");
    }

    // Instrumentation uses an isolated namespace to exercise empty/broken installs safely.
    QnxDeployer(Context context, QnxControl control, String baseDirectory) {
        if (!baseDirectory.matches("/var/log/fcw(?:_test_[a-f0-9]+)?")) throw new IllegalArgumentException("QNX directory");
        this.context = context.getApplicationContext();
        this.control = control;
        this.baseDirectory = baseDirectory;
    }

    String ensureDeployed() throws Exception {
        QnxPayload payload;
        // Missing/invalid metadata means a broken APK, never a reason to skip verification.
        try (InputStream in = context.getAssets().open("qnx/manifest.properties")) {
            payload = QnxPayload.read(in);
        }
        String directory = baseDirectory + "/apk_" + payload.id();
        ensureDirectory(baseDirectory);
        String state = control.qnx(payload.probe(directory), 3);
        require(state, "PAYLOAD_CHECKED", "QNX не подтвердил проверку компонентов");
        if (QnxControl.marker(state, "PAYLOAD_OK")) return directory;
        ensureDirectory(directory);
        boolean nfs = true;
        try { ensureNfs(); }
        catch (Exception e) { nfs = false; android.util.Log.w("QnxDeployer", "NFS unavailable, using telnet", e); }
        for (QnxPayload.Entry entry : payload.entries) {
            if (QnxControl.marker(state, "ENTRY_OK_" + entry.name)) continue;
            IOException last = null;
            for (int attempt = nfs ? 0 : 2; attempt < 3; attempt++) {
                try { deploy(entry, directory, attempt < 2); last = null; break; }
                catch (IOException failure) {
                    last = failure;
                    android.util.Log.w("QnxDeployer", "Transfer attempt " + attempt + " for " + entry.name, failure);
                }
            }
            if (last != null) throw last;
        }
        require(control.qnx(payload.probe(directory), 3), "PAYLOAD_OK", "Проверка комплекта QNX не пройдена");
        return directory;
    }

    private void ensureDirectory(String directory) throws Exception {
        String backup = directory + ".broken_" + UUID.randomUUID().toString().replace("-", "");
        String command = "d=" + directory + "\nb=" + backup + "\n"
                + "if [ -L $d ] || { [ -e $d ] && [ ! -d $d ]; }; then mv $d $b || exit 1; fi\n"
                + "mkdir -p $d && chmod 755 $d && echo DIRECTORY_OK";
        require(control.qnx(command, 2), "DIRECTORY_OK", "Не удалось подготовить каталог QNX");
    }

    private void ensureNfs() throws Exception {
        // Never copy into an unmounted directory or an unrelated export.
        try (AdbClient adb = control.createAdbClient(20000)) {
            String mounted = "awk '$1 == \"172.16.1.5:/var/log\" && $2 == \"" + NFS
                    + "\" && $3 == \"nfs\" {ok=1} END {exit !ok}' /proc/mounts";
            String mount = "if ! " + mounted + "; then "
                    + "if awk '$2 == \"" + NFS + "\" {found=1} END {exit !found}' /proc/mounts; then exit 1; fi; "
                    + "mkdir -p " + NFS + " && timeout 10 busybox mount -t nfs -o vers=3,nolock,proto=tcp,soft,timeo=20,retrans=2 "
                    + "172.16.1.5:/var/log " + NFS + " || exit 1; fi; " + mounted + " && echo NFS_OK";
            require(adb.shell(mount), "NFS_OK", "Недоступен NFS-ресурс QNX");
        }
    }

    private void deploy(QnxPayload.Entry entry, String directory, boolean useNfs) throws Exception {
        File stage = File.createTempFile("qnx-", ".part", context.getCacheDir());
        // Unique flat NFS names avoid stale Android NFS directory entries.
        String name = "fcw_in_" + UUID.randomUUID().toString().replace("-", "");
        String remote = "/var/log/" + name;
        try {
            try (InputStream in = context.getAssets().open("qnx/" + entry.name);
                 FileOutputStream out = new FileOutputStream(stage)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            entry.verify(stage);
            // root-adbd reads private cache; no world-readable app data is needed.
            if (useNfs) {
                // Creating/truncating files through F515's NFS client is unreliable. Create
                // the unique staging inode locally on QNX, then only write its existing handle.
                require(control.qnx("(umask 000; : > " + remote + ") && chmod 666 " + remote + " && echo STAGE_READY", 2),
                        "STAGE_READY", "QNX не подготовил временный файл");
                try (AdbClient adb = control.createAdbClient(45000)) {
                    // F515's cp can report success with a zero-byte NFS file. dd was verified
                    // against the same export and writes the complete payload.
                    String copy = adb.shell("timeout 30 busybox dd if=" + QnxControl.quote(stage.getAbsolutePath())
                            + " of=" + NFS + "/" + name + " bs=65536 conv=notrunc && timeout 10 sync && echo COPY_OK");
                    if (!QnxControl.marker(copy, "COPY_OK")) android.util.Log.w("QnxDeployer", copy);
                    require(copy, "COPY_OK", "Ошибка копирования " + entry.name + " в QNX");
                }
            } else control.upload(stage, remote);
            // Verify on QNX BEFORE replacing anything, then rename on the same filesystem.
            String target = directory + "/" + entry.name;
            // Keep telnet input lines short, including long recovery paths.
            String cmd = "src=" + remote + "\ndst=" + target + "\nbackup=" + target + ".broken_" + name + "\n"
                    + "if " + entry.check("$src") + "; then\n"
                    + "if [ -d $dst ] || [ -L $dst ]; then mv $dst $backup || exit 1; fi\n"
                    + "chmod " + entry.mode + " $src && mv -f $src $dst && echo DEPLOY_OK\n"
                    + "else ls -ln $src; openssl dgst -sha256 $src; fi";
            String response = control.qnx(cmd, 5);
            if (!QnxControl.marker(response, "DEPLOY_OK")) android.util.Log.w("QnxDeployer", response);
            require(response, "DEPLOY_OK", "QNX: повреждён или не установлен " + entry.name);
        } finally {
            stage.delete();
            try { control.qnx("rm -f " + remote, 1); } catch (Exception ignored) { }
        }
    }

    private static void require(String output, String marker, String message) throws IOException {
        if (!QnxControl.marker(output, marker)) throw new IOException(message);
    }
}
