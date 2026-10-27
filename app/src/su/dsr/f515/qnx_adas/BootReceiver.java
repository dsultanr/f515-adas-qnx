package su.dsr.f515.qnx_adas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the background service only; never opens an Activity. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!AutostartSettings.isEnabled(context)) return;
        // BOOT_COUNT is available on F515. The fallback also supports systems without it.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) && AutostartSettings.bootCount(context) < 0)
            AutostartSettings.resume(context);
        if (!AutostartSettings.shouldStart(context)) return;
        try {
            context.startForegroundService(new Intent(context, FcwdLinkService.class));
        } catch (RuntimeException e) {
            Log.e("QnxAdas", "Autostart service could not be started", e);
        }
    }
}
