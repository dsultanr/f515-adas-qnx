package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

/** Exercises installed app credentials and QNX payload without starting recognition. */
public final class MaintenanceDeviceTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b) { super.onCreate(b); args = b; start(); }
    private void status(String value) { Bundle b = new Bundle(); b.putString("stream", value + "\n"); sendStatus(0, b); }
    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle(); boolean ok = false;
        try {
            Context c = getTargetContext(); QnxControl q = new QnxControl(c);
            check(!q.running(), "fcwd must be stopped before maintenance test");
            String path = new QnxDeployer(c, q).ensureDeployed();
            status("PASS install verified QNX payload: " + path);
            check(path.equals(new QnxDeployer(c, q).ensureDeployed()), "Repeated deployment changed path");
            check(!q.running(), "Deploying files unexpectedly started fcwd");
            status("PASS repeated verification; fcwd remains stopped");
            String permissions = new PermissionHelper(c).grant();
            status("PASS local-root permissions: " + permissions);
            try (AdbClient adb = q.createAdbClient(5000)) {
                check("first".equals(adb.shell("printf first").trim()), "First ADB stream");
                check("second".equals(adb.shell("printf second").trim()), "Second ADB stream");
            }
            status("PASS sequential authenticated ADB streams");
            if (args != null && "true".equals(args.getString("repair"))) testRepair(c, q);
            if (args != null && "true".equals(args.getString("fallback"))) testFallback(c, q);
            if (args != null && "true".equals(args.getString("cloud"))) {
                ReleaseMetadata r = UpdateManager.check();
                status("PASS HTTPS release feed: " + r.version + " / " + r.code + " / " + r.size);
                if ("true".equals(args.getString("install"))) {
                    UpdateManager.downloadAndInstall(c, r, this::status);
                    status("UPDATE_COMMITTED; check PackageInstaller result and installed version externally");
                }
            }
            ok = true; result.putString("stream", "PASS ALL maintenance checks\n");
        } catch (Throwable e) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e)); }
        finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void testFallback(Context c, QnxControl q) throws Exception {
        java.io.File stage = java.io.File.createTempFile("qnx-test-", ".dlc", c.getCacheDir());
        String remote = "/var/log/fcw_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            QnxPayload payload;
            try (java.io.InputStream in = c.getAssets().open("qnx/manifest.properties")) { payload = QnxPayload.read(in); }
            QnxPayload.Entry model = payload.entries[1];
            try (java.io.InputStream in = c.getAssets().open("qnx/" + model.name);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(stage)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            q.upload(stage, remote);
            check(QnxControl.marker(q.qnx("if " + model.check(remote) + "; then echo FALLBACK_OK; fi", 3), "FALLBACK_OK"),
                    "Fallback model size/hash mismatch");
            status("PASS full 5.7 MB model through telnet fallback, SHA-256 verified on QNX");
        } finally { stage.delete(); q.qnx("rm -f " + remote, 2); }
    }

    private void testRepair(Context c, QnxControl q) throws Exception {
        String base = "/var/log/fcw_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        QnxDeployer deployer = new QnxDeployer(c, q, base);
        try {
            String path = deployer.ensureDeployed();
            status("PASS empty QNX directory: complete payload installed from APK");
            // Same-size corruption of a private test copy, never of an active payload.
            check(QnxControl.marker(q.qnx("printf BAD | dd of=" + path + "/fcwd bs=1 count=3 conv=notrunc && chmod 000 "
                    + path + "/autospeed_heads3o_int8.dlc && echo TEST_SEEDED", 2), "TEST_SEEDED"), "Could not seed corruption");
            check(path.equals(deployer.ensureDeployed()), "Repair selected a different release");
            status("PASS same-size corrupt binary and wrong model permissions repaired");
            check(QnxControl.marker(q.qnx("rm -f " + path + "/autospeed_heads3o_int8.dlc && mkdir " + path + "/autospeed_heads3o_int8.dlc && echo TEST_SEEDED", 2), "TEST_SEEDED"), "Could not seed partial install");
            deployer.ensureDeployed();
            status("PASS interrupted install / directory instead of model repaired");
            check(QnxControl.marker(q.qnx("mv " + base + " " + base + "_saved && echo partial > " + base + " && echo TEST_SEEDED", 2), "TEST_SEEDED"), "Could not seed bad directory");
            deployer.ensureDeployed();
            status("PASS file instead of installation directory preserved and repaired");
        } finally {
            // Only this test's UUID namespace, including recovery backups, is removed.
            q.qnx("rm -rf " + base + " " + base + "_saved " + base + ".broken_*", 3);
            check(!q.running(), "Repair unexpectedly launched fcwd");
        }
    }
}
