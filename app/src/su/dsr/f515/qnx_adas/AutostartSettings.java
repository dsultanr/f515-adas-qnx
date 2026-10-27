package su.dsr.f515.qnx_adas;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Opt-in startup; a manual stop remains effective for the current Android boot. */
final class AutostartSettings {
    static final String ENABLED = "autostart";
    static final String PAUSED_BOOT = "autostart_paused_boot";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE);
    }

    static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    static boolean isEnabled(Context c) { return prefs(c).getBoolean(ENABLED, false); }

    static boolean shouldStart(Context c) {
        return isEnabled(c) && prefs(c).getInt(PAUSED_BOOT, Integer.MIN_VALUE) != bootCount(c);
    }

    static void setEnabled(Context c, boolean enabled) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, BootReceiver.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(ENABLED, enabled);
        if (enabled) e.remove(PAUSED_BOOT);
        e.apply();
    }

    static void pauseUntilNextBoot(Context c) { prefs(c).edit().putInt(PAUSED_BOOT, bootCount(c)).apply(); }
    static void resume(Context c) { prefs(c).edit().remove(PAUSED_BOOT).apply(); }
}
