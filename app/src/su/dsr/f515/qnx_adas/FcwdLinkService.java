package su.dsr.f515.qnx_adas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Принимает поток fcwd с QNX (TCP 62715, JSON-строки), пересылает кадры экрану, играет звук первого FCW
 * только на втором уровне и читает скорость VHAL (SPEED_DISP) для сверки со скоростью PPS.
 * Фаервол Android пропускает от QNX входящий TCP только на 60000-65000, поэтому порт 62715.
 */
public class FcwdLinkService extends Service {
    static final String ACTION_FRAME = "su.dsr.f515.qnx_adas.FRAME";
    static final String ACTION_LINK = "su.dsr.f515.qnx_adas.LINK";
    static final String PREFS = "qnx_adas";
    static final int PORT = 62715;
    private static final String TAG = "QnxAdas";

    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile Socket peer;
    private volatile AdbClient vhalBridge;
    private volatile SpeedReading vhal = SpeedReading.UNKNOWN;
    private long lastBroadcast;
    private int lastState;
    private volatile long lastFrameAt;
    private ScheduledExecutorService autostartExecutor;
    private ScheduledFuture<?> autostartTask;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (p, key) -> {
        if (AutostartSettings.ENABLED.equals(key) || AutostartSettings.PAUSED_BOOT.equals(key)) refreshAutostart();
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("link", "Связь с QNX", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "link")
                .setContentTitle("F515 ADAS · QNX")
                .setContentText("Приём предупреждений fcwd (DSP)")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        startForeground(1, n);
        NavigationAudioBuzzer.init(this);
        running = true;
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        autostartExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "QnxAdasAutostart"));
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        new Thread(this::serve, "QnxAdasLink").start();
        new Thread(this::runVhal, "QnxAdasVhal").start();
        refreshAutostart();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        running = false;
        preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        autostartExecutor.shutdownNow();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (peer != null) peer.close(); } catch (Exception ignored) {}
        AdbClient b = vhalBridge;
        if (b != null) b.close();
        NavigationAudioBuzzer.release();
        super.onDestroy();
    }

    private void serve() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                while (running) {
                    try (Socket s = ss.accept()) {
                        peer = s;
                        s.setSoTimeout(3000);
                        link(true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String line;
                        while (running && (line = in.readLine()) != null) onLine(line);
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "link: " + e);
                    } finally {
                        peer = null;
                    }
                    link(false);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "server: " + e);
                SystemClock.sleep(2000);
            }
        }
    }

    private void link(boolean up) {
        if (!up) NavigationAudioBuzzer.stop();
        Intent i = new Intent(ACTION_LINK).setPackage(getPackageName());
        i.putExtra("up", up);
        sendBroadcast(i);
    }

    private void onLine(String line) {
        if (!running) return;
        int state;
        try { state = FcwdFrame.parse(line).state; } catch (Exception e) {
            NavigationAudioBuzzer.stop();
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        NavigationAudioBuzzer.onWarningState(state, p.getBoolean("sound", true));
        lastFrameAt = SystemClock.elapsedRealtime();
        long now = SystemClock.uptimeMillis();
        if (state != lastState || now - lastBroadcast >= 50) {
            Intent i = new Intent(ACTION_FRAME).setPackage(getPackageName());
            i.putExtra("json", line);
            SpeedReading v = vhal;
            i.putExtra("vhal_speed", v.fresh(SystemClock.elapsedRealtime(), 1200) ? v.kmh : Float.NaN);
            sendBroadcast(i);
            lastBroadcast = now;
        }
        lastState = state;
    }

    private synchronized void refreshAutostart() {
        if (!running || !AutostartSettings.shouldStart(this)) {
            if (autostartTask != null) autostartTask.cancel(false);
            autostartTask = null;
            return;
        }
        if (autostartTask != null && !autostartTask.isDone()) return;
        // QNX/adbd may still be booting. A fresh stream needs no process probe.
        autostartTask = autostartExecutor.scheduleWithFixedDelay(() -> {
            if (!running || (lastFrameAt > 0 && SystemClock.elapsedRealtime() - lastFrameAt < 5000)) return;
            try {
                new QnxControl(this).ensureRunning(() -> running && AutostartSettings.shouldStart(this));
            } catch (Exception e) {
                if (running) Log.w(TAG, "Autostart waiting for QNX: " + e);
            }
        }, 2, 10, TimeUnit.SECONDS);
    }

    private void runVhal() {
        while (running) {
            try (AdbClient client = new AdbClient("127.0.0.1", 5555, asset("adbkey"), asset("adbkey.pub"), 3000)) {
                vhalBridge = client;
                String apk = "'" + getApplicationInfo().sourceDir.replace("'", "'\\''") + "'";
                client.shellLines("CLASSPATH=" + apk + ":/system/framework/android.car.jar exec app_process /system/bin"
                        + " --nice-name=QnxAdasVhal " + VhalSpeedBridge.class.getName(), line -> {
                    if (line.startsWith("SPEED ")) vhal = SpeedReading.parseBridge(line, SystemClock.elapsedRealtime());
                });
            } catch (Exception e) {
                if (running) Log.w(TAG, "vhal: " + e);
            } finally { vhalBridge = null; }
            SystemClock.sleep(2000);
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
