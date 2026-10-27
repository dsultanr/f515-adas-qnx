package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    static final String LATEST_URL = "https://tm.dsr.su/f515/adas-qnx/latest.json";
    static final String STATUS = "update_status";
    static final AtomicBoolean BUSY = new AtomicBoolean();
    interface Progress { void line(String message); }

    static PackageInfo installed(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
    }
    static String version(Context c) {
        try { return installed(c).versionName; } catch (Exception e) { return "?"; }
    }
    static void status(Context c, String message) {
        c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE).edit().putString(STATUS, message).apply();
    }
    static ReleaseMetadata check() throws Exception {
        HttpURLConnection conn = connect(LATEST_URL);
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > 65536) throw new IOException("Слишком большой файл обновления");
                out.write(b, 0, n);
            }
            JSONObject j = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return new ReleaseMetadata(j.getString("version"), j.getLong("version_code"), j.optString("body"),
                    j.getString("apk_url"), j.getLong("size"), j.getString("sha256"));
        } finally { conn.disconnect(); }
    }
    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) ReleaseMetadata.validateUrl(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "F515-QnxAdas");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("tm.dsr.su: HTTP " + code);
            return c;
        } catch (IOException e) { c.disconnect(); throw e; }
    }
    static void downloadAndInstall(Context c, ReleaseMetadata release, Progress progress) throws Exception {
        if (release.code <= installed(c).versionCode) throw new IOException("Эта версия уже установлена");
        new PermissionHelper(c).ensureInstallPermission();
        File apk = File.createTempFile("adas-update-", ".apk", c.getCacheDir());
        HttpURLConnection conn = null;
        try {
            conn = connect(release.url);
            long length = conn.getContentLengthLong();
            if (length >= 0 && length != release.size) throw new IOException("Размер APK не совпадает с описанием");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] b = new byte[65536]; long total = 0; int n, last = -1;
                long deadline = System.nanoTime() + 300_000_000_000L;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > release.size || System.nanoTime() > deadline) throw new IOException("Превышен размер или время загрузки APK");
                    out.write(b, 0, n);
                    int percent = (int) (100 * total / release.size);
                    if (percent / 10 != last) { last = percent / 10; progress.line("Загрузка: " + percent + "%"); }
                }
                out.getFD().sync();
                if (total != release.size) throw new IOException("APK загружен не полностью");
            }
            verifyArchive(c, apk, release);
            progress.line("APK проверен. Подготовка установки…");
            ApkInstaller.install(c, apk);
        } finally { if (conn != null) conn.disconnect(); apk.delete(); }
    }
    static void verifyArchive(Context c, File apk, ReleaseMetadata release) throws Exception {
        if (apk.length() != release.size || !release.sha256.equals(QnxPayload.hash(apk)))
            throw new IOException("Контрольная сумма APK не совпадает");
        PackageInfo target = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo current = installed(c);
        if (target == null || !c.getPackageName().equals(target.packageName)
                || target.versionCode != release.code || !release.version.equals(target.versionName)
                || target.versionCode <= current.versionCode)
            throw new IOException("APK не соответствует приложению или версии обновления");
        Set<Signature> a = signatures(current), b = signatures(target);
        if (a.isEmpty() || !a.equals(b)) throw new IOException("Подпись APK отличается от установленного приложения");
    }
    private static Set<Signature> signatures(PackageInfo p) {
        Set<Signature> result = new HashSet<>();
        if (p.signatures != null) java.util.Collections.addAll(result, p.signatures);
        return result;
    }
}
