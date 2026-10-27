package su.dsr.f515.qnx_adas;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Запуск/остановка fcwd на QNX: root-shell через локальный adbd (AdbClient) -> busybox telnet 172.16.1.5. */
final class QnxControl {
    private static final Object CONTROL_LOCK = new Object();
    // QNX pidin may omit the initial slash from the executable path.
    private static final String IS_RUNNING = "pidin -p fcwd -f an 2>/dev/null | grep -q '[[:space:]]/*dev/shmem/fcwd[[:space:]]*$'";
    private final Context context;
    private final QnxDeployer deployer;

    QnxControl(Context context) {
        this.context = context.getApplicationContext();
        this.deployer = new QnxDeployer(this.context, this);
    }

    AdbClient createAdbClient(int timeoutMs) throws Exception {
        return new AdbClient("127.0.0.1", 5555, asset("adbkey"), asset("adbkey.pub"), timeoutMs);
    }

    /** Выполнить команду в QNX; возвращает вывод (с эхом telnet). */
    String qnx(String command, int waitSec) throws Exception {
        // telnet can discard stdin received before connecting. Wait before sending the login.
        String end = "QNX_DONE_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String sh = "{ sleep 1; echo root; sleep 2; echo; sleep 1; printf '%s\\n' "
                + quote(command + "\necho " + end) + "; sleep " + waitSec
                + "; echo exit; } | timeout " + (waitSec + 12) + " busybox telnet 172.16.1.5 2>/dev/null";
        try (AdbClient adb = createAdbClient((waitSec + 15) * 1000)) {
            String result = adb.shellUntil(sh, end);
            if (!marker(result, end)) throw new IOException("QNX не подтвердил завершение команды: проверьте связь с ГУ");
            return result;
        }
    }

    String prepare() throws Exception {
        synchronized (CONTROL_LOCK) { return deployer.ensureDeployed(); }
    }

    /** Fallback used when the firmware NFS client reports success but writes an empty file. */
    void upload(java.io.File source, String destination) throws Exception {
        String end = "QNX_UPLOAD_" + java.util.UUID.randomUUID().toString().replace("-", "");
        int wait = (int) (source.length() / 65536) + 30;
        // Feed OpenSSL directly. QNX's interactive shell restores echo while reading a
        // heredoc; a multi-MB heredoc then deadlocks busybox telnet's duplex buffers.
        // Canonical EOF (Ctrl-D after base64's final newline) ends OpenSSL's input only.
        String header = "stty -echo; openssl base64 -d -out " + destination;
        String shell = "{ sleep 1; echo root; sleep 2; echo; sleep 1; printf '%s\\n' "
                + quote(header) + "; sleep 1; busybox base64 " + quote(source.getAbsolutePath())
                + "; printf '\\004'; sleep 1; echo 'echo; echo " + end + "'; sleep " + wait
                + "; echo exit; } | timeout " + (wait + 30) + " busybox telnet 172.16.1.5 2>/dev/null";
        try (AdbClient adb = createAdbClient((wait + 40) * 1000)) {
            if (!marker(adb.shellUntil(shell, end), end)) throw new IOException("QNX не подтвердил резервную передачу файла");
        }
    }

    boolean running() throws Exception {
        synchronized (CONTROL_LOCK) {
            String out = qnx("if " + IS_RUNNING + "; then echo FCWD_RUNNING; else echo FCWD_STOPPED; fi", 2);
            if (marker(out, "FCWD_RUNNING")) return true;
            if (marker(out, "FCWD_STOPPED")) return false;
            throw new IOException("QNX did not report fcwd status");
        }
    }

    void start(boolean clusterBanner) throws Exception {
        synchronized (CONTROL_LOCK) {
            String directory = deployer.ensureDeployed();
            requireStarted(qnx("slay -s TERM fcwd 2>/dev/null; sleep 1; if " + IS_RUNNING
                    + "; then echo FCWD_STOP_FAILED; else\n" + launch(clusterBanner, directory) + "\nfi", 4));
            AutostartSettings.resume(context);
        }
    }

    void ensureRunning(BooleanSupplier allowed) throws Exception {
        synchronized (CONTROL_LOCK) {
            // Recheck inside the lock so an in-flight retry cannot undo a manual stop.
            if (!allowed.getAsBoolean()) return;
            if (running()) return;
            String directory = deployer.ensureDeployed();
            // Deployment may take time; disabling autostart must still prevent launch.
            if (!allowed.getAsBoolean()) return;
            boolean banner = context.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE).getBoolean("banner", true);
            requireStarted(qnx("if " + IS_RUNNING + "; then echo FCWD_RUNNING; else\n" + launch(banner, directory) + "\nfi", 4));
        }
    }

    void stop() throws Exception {
        synchronized (CONTROL_LOCK) {
            AutostartSettings.pauseUntilNextBoot(context);
            String out = qnx("slay -s TERM fcwd 2>/dev/null; sleep 1; if " + IS_RUNNING
                    + "; then echo FCWD_STOP_FAILED; else echo FCWD_STOPPED; fi", 3);
            if (!marker(out, "FCWD_STOPPED")) throw new IOException("QNX did not confirm fcwd stop");
        }
    }

    private static String launch(boolean banner, String directory) {
        return "if cp " + directory + "/fcwd /dev/shmem/fcwd && chmod 755 /dev/shmem/fcwd; then\n"
                + "(trap '' HUP; export ADSP_LIBRARY_PATH=/etc/images/dsp; exec /dev/shmem/fcwd pps=" + (banner ? 1 : 0)
                + " dlc=" + directory + "/autospeed_heads3o_int8.dlc </dev/null >/dev/shmem/fcwd.log 2>&1) & sleep 1;\n"
                + "if " + IS_RUNNING + "; then echo FCWD_RUNNING; else echo FCWD_START_FAILED; tail -n 2 /dev/shmem/fcwd.log 2>/dev/null; fi; "
                + "else echo FCWD_START_FAILED; fi;";
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static boolean marker(String output, String expected) {
        for (String line : output.split("\n")) if (expected.equals(line.trim())) return true;
        return false;
    }

    private static void requireStarted(String output) throws IOException {
        if (!marker(output, "FCWD_RUNNING")) {
            String diag = "";
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.startsWith("[snpe]") || line.startsWith("[cam]") || line.startsWith("ldd:") || line.startsWith("sh:")) {
                    diag = line;
                    break;
                }
            }
            throw new IOException("QNX did not confirm fcwd start" + (diag.isEmpty() ? "" : ": " + diag));
        }
    }

    byte[] asset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
