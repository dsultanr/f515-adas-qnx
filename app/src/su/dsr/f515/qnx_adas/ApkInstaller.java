package su.dsr.f515.qnx_adas;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.*;

/** Session API used by Geofence; result receiver survives activity/process recreation. */
final class ApkInstaller {
    static final String ACTION = "su.dsr.f515.qnx_adas.INSTALL_RESULT";
    static final String SESSION = "update_session";
    static void install(Context c, File apk) throws Exception {
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            new PermissionHelper(c).ensureInstallPermission();
        }
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo pending : installer.getMySessions()) {
            if (c.getPackageName().equals(pending.getAppPackageName())) {
                // A process/power loss while writing can leave an unfinished session behind.
                if (!pending.isSealed()) installer.abandonSession(pending.getSessionId());
                else throw new IOException("Установка уже ожидает завершения. Подтвердите её на экране ГУ.");
            }
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName()); params.setSize(apk.length());
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                session.fsync(out);
            }
            c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE).edit().putInt(SESSION, id).commit();
            Intent result = new Intent(c, InstallResultReceiver.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0);
            PendingIntent callback = PendingIntent.getBroadcast(c, id, result, flags);
            UpdateManager.status(c, "Установка передана системе. Подтвердите её на экране ГУ.");
            session.commit(callback.getIntentSender()); committed = true;
        } finally {
            if (!committed) {
                installer.abandonSession(id);
                c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE).edit().remove(SESSION).apply();
            }
        }
    }
}
