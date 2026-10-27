package su.dsr.f515.qnx_adas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

public final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!ApkInstaller.ACTION.equals(intent.getAction())) return;
        SharedPreferences p = c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE);
        int expected = p.getInt(ApkInstaller.SESSION, -1);
        if (expected < 0 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != expected) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm == null) throw new IllegalStateException("Нет диалога подтверждения");
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                UpdateManager.status(c, "Подтвердите обновление на экране ГУ.");
            } catch (Exception e) {
                c.getPackageManager().getPackageInstaller().abandonSession(expected);
                p.edit().remove(ApkInstaller.SESSION).apply();
                UpdateManager.status(c, "Не удалось открыть установщик: " + e.getMessage());
            }
            return;
        }
        p.edit().remove(ApkInstaller.SESSION).apply();
        if (status == PackageInstaller.STATUS_SUCCESS) UpdateManager.status(c, "Обновление установлено.");
        else UpdateManager.status(c, "Установка не завершена: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                + (status == PackageInstaller.STATUS_FAILURE_BLOCKED ? "\nПроверьте в Toolbox разрешение сторонних установщиков." : ""));
    }
}
