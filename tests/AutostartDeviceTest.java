package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requires an idle fcwd. All temporary starts are muted and have cluster output disabled. */
public final class AutostartDeviceTest extends Instrumentation {
    private boolean captureDuringRecognition;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        captureDuringRecognition = args != null && "true".equals(args.getString("snapshot"));
        start();
    }
    private void status(String text) {
        Bundle b = new Bundle(); b.putString("stream", text + "\n"); sendStatus(0, b);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition, long timeout, String message) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private static int pid(QnxControl q) throws Exception {
        String output = q.qnx("pidin -p fcwd -f an", 2);
        Matcher m = Pattern.compile("(?m)^\\s*(\\d+)\\s+/?dev/shmem/fcwd\\s*$").matcher(output);
        int pid = 0, count = 0;
        while (m.find()) { pid = Integer.parseInt(m.group(1)); count++; }
        check(count == 1, "Expected one fcwd, got " + count + ": " + output);
        return pid;
    }
    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor e = prefs.edit().clear();
        for (Map.Entry<String, ?> item : saved.entrySet()) {
            String k = item.getKey(); Object v = item.getValue();
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        }
        check(e.commit(), "Preference restore failed");
    }
    @Override public void onStart() {
        Context c = getTargetContext();
        SharedPreferences prefs = c.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        ComponentName boot = new ComponentName(c, BootReceiver.class);
        int component = c.getPackageManager().getComponentEnabledSetting(boot);
        QnxControl q = new QnxControl(c);
        AtomicInteger frames = new AtomicInteger();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent i) { frames.incrementAndGet(); }
        };
        boolean changed = false, registered = false, ok = false;
        String failure = "";
        try {
            check(!AutostartSettings.isEnabled(c), "Autostart is already enabled; leave user's session untouched");
            check(!q.running(), "fcwd is already running; leave user's session untouched");
            check(!c.getPackageManager().getReceiverInfo(boot, PackageManager.MATCH_DISABLED_COMPONENTS).enabled,
                    "Boot receiver should be disabled before opting in");
            changed = true;
            prefs.edit().putBoolean("sound", false).putBoolean("banner", false).commit();
            c.registerReceiver(receiver, new IntentFilter(FcwdLinkService.ACTION_FRAME)); registered = true;
            BootReceiver r = new BootReceiver();
            r.onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
            SystemClock.sleep(2200);
            check(frames.get() == 0 && !q.running(), "Disabled autostart started fcwd");
            status("PASS default off: no recognition or boot receiver");

            AutostartSettings.setEnabled(c, true);
            // ComponentInfo.enabled is the manifest value on this firmware. Read the runtime override.
            check(c.getPackageManager().getComponentEnabledSetting(boot) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    "Opt-in did not enable receiver");
            int currentBoot = AutostartSettings.bootCount(c);
            check(currentBoot >= 0, "F515 BOOT_COUNT unavailable");
            prefs.edit().putInt(AutostartSettings.PAUSED_BOOT, currentBoot - 1).commit();
            check(AutostartSettings.shouldStart(c), "Previous boot's manual stop suppressed this boot");
            r.onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
            await(() -> frames.get() >= 5, 180000, "Autostart did not deliver fcwd frames");
            int firstPid = pid(q);
            status("PASS enabled boot: fcwd pid=" + firstPid + ", frames=" + frames.get());

            if (captureDuringRecognition) {
                int beforeCapture = frames.get();
                java.io.File archive = CalibrationArchive.capture(c, false);
                check(pid(q) == firstPid, "Snapshot restarted running fcwd");
                await(() -> frames.get() >= beforeCapture + 5, 10000, "Recognition did not continue during snapshot");
                status("PASS snapshot alongside recognition: same PID, frames=" + frames.get() + ", archive=" + archive.getName());
            }

            r.onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
            r.onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
            q.ensureRunning(() -> AutostartSettings.shouldStart(c));
            check(pid(q) == firstPid, "Duplicate boot/update restarted fcwd");
            status("PASS duplicate boot/update: same single fcwd process");

            q.stop();
            check(!AutostartSettings.shouldStart(c), "Manual stop did not suspend autostart");
            c.stopService(new Intent(c, FcwdLinkService.class));
            waitForIdleSync(); SystemClock.sleep(600);
            c.startForegroundService(new Intent(c, FcwdLinkService.class));
            r.onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
            r.onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
            SystemClock.sleep(4000);
            check(!q.running(), "Manual stop was undone by service restart or duplicate boot");
            status("PASS manual stop survives service restart and update in the same boot");

            int before = frames.get();
            AutostartSettings.setEnabled(c, true);
            await(() -> frames.get() >= before + 5, 60000, "Re-enabling autostart failed");
            check(q.running(), "Re-enabled fcwd not running");
            status("PASS explicit re-enable resumes recognition");

            AutostartSettings.setEnabled(c, false);
            check(q.running(), "Disabling future autostart stopped current recognition");
            q.stop(); c.stopService(new Intent(c, FcwdLinkService.class)); waitForIdleSync();
            r.onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED)); SystemClock.sleep(2500);
            check(!q.running(), "Disabled autostart launched after boot");
            check(c.getPackageManager().getComponentEnabledSetting(boot) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    "Disabled preference left the boot receiver enabled");
            status("PASS opt-out: boot receiver disabled, no automatic launch");
            ok = true;
        } catch (Throwable e) {
            failure = android.util.Log.getStackTraceString(e);
        } finally {
            if (changed) {
                try {
                    AutostartSettings.setEnabled(c, false); q.stop();
                    c.stopService(new Intent(c, FcwdLinkService.class)); waitForIdleSync();
                    restore(prefs, saved);
                    c.getPackageManager().setComponentEnabledSetting(boot, component, PackageManager.DONT_KILL_APP);
                    check(!q.running(), "fcwd cleanup failed");
                    status("RESTORED preferences, boot receiver and stopped fcwd");
                } catch (Throwable e) { ok = false; failure += "\nCleanup: " + android.util.Log.getStackTraceString(e); }
            }
            if (registered) c.unregisterReceiver(receiver);
        }
        Bundle result = new Bundle();
        result.putString("stream", ok ? "PASS ALL: optional autostart; no reboot, sound or cluster banners used\n" : "FAIL: " + failure);
        finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
