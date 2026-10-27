package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import java.io.IOException;

/** Same local-root mechanism as Vega, scoped to this application's needs. */
final class PermissionHelper {
    static final String PENDING = "permission_setup_pending";
    private final Context context;
    PermissionHelper(Context context) { this.context = context.getApplicationContext(); }
    boolean canInstall() { return context.getPackageManager().canRequestPackageInstalls(); }
    boolean batteryExempt() {
        return context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName());
    }
    String status() {
        return "Установка обновлений: " + (canInstall() ? "разрешена" : "нужно разрешение")
                + "\nРабота без оптимизации батареи: " + (batteryExempt() ? "разрешена" : "нужно разрешение");
    }
    String grant() throws Exception {
        try (AdbClient adb = connectRoot()) {
            String battery = adb.shell("dumpsys deviceidle whitelist +" + context.getPackageName()
                    + " </dev/null 2>&1 && echo BATTERY_ALLOWED");
            if (!QnxControl.marker(battery, "BATTERY_ALLOWED") || !batteryExempt())
                throw new IOException("Не удалось разрешить работу в фоне.\n" + status());
            if (!canInstall()) {
                requestInstallGrant(adb);
                return "Разрешение на обновления включается. Приложение сейчас откроется заново.";
            }
            return "Локальный доступ к ГУ: root-ADB доступен\n" + status();
        }
    }
    void ensureInstallPermission() throws Exception {
        if (canInstall()) return;
        try (AdbClient adb = connectRoot()) { requestInstallGrant(adb); }
        throw new IOException("Разрешение на обновления включается. После перезапуска повторите установку.");
    }
    private AdbClient connectRoot() throws Exception {
        AdbClient adb = new QnxControl(context).createAdbClient(15000);
        try {
            if (!"0".equals(adb.shell("id -u").trim())) throw new IOException("Локальный ADB не имеет root-прав");
            return adb;
        } catch (Exception e) { adb.close(); throw e; }
    }
    private void requestInstallGrant(AdbClient adb) throws Exception {
        // F515 kills the UID on REQUEST_INSTALL_PACKAGES changes. Finish outside that UID,
        // survive ADB disconnect, then reopen the explicit activity to report actual state.
        String pkg = context.getPackageName(); int user = Process.myUid() / 100000;
        if (!context.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PENDING, true).commit()) throw new IOException("Не удалось сохранить состояние настройки");
        String command = "(trap '' HUP; sleep 1; cmd appops set --user " + user + " " + pkg
                + " REQUEST_INSTALL_PACKAGES allow; sleep 2; am start --user " + user + " -n " + pkg
                + "/.MainActivity) </dev/null >/dev/null 2>&1 & echo PERMISSION_SETUP_STARTED";
        if (!QnxControl.marker(adb.shell(command), "PERMISSION_SETUP_STARTED"))
            throw new IOException("Не удалось запустить настройку прав");
    }
}
