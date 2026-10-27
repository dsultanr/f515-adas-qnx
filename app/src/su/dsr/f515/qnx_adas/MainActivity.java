package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** F515 ADAS · QNX: экран предупреждений fcwd (AutoSpeed INT8 на Hexagon DSP в QNX). Нейросеть в Android не запускается. */
public class MainActivity extends Activity {
    private AdasRadarView radar;
    private CameraBoxesView boxes;
    private TextView status;
    private MaintenancePanel maintenance;
    private CalibrationPanel calibration;
    private Button startButton, stopButton;
    private TextView controlStatus;
    private boolean linkUp;
    private long lastFrameAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (FcwdLinkService.ACTION_LINK.equals(intent.getAction())) {
                linkUp = intent.getBooleanExtra("up", false);
                refreshStatus(null, Float.NaN);
                return;
            }
            FcwdFrame f;
            try { f = FcwdFrame.parse(intent.getStringExtra("json")); } catch (Exception e) { return; }
            linkUp = true;
            lastFrameAt = SystemClock.uptimeMillis();
            float vhal = intent.getFloatExtra("vhal_speed", Float.NaN);
            float speed = Float.isFinite(vhal) ? vhal : f.speedPps;
            FcwdFrame.Obj t = f.target();
            // дистанция: оценка VP (фильтр), иначе по нижней кромке цели
            float d = f.vpCipo && f.vpD > 0 ? f.vpD : t != null ? FcwdFrame.groundDistance(t) : Float.NaN;
            float vrel = f.vpCipo ? f.vpVel : Float.NaN;
            boolean found = t != null && Float.isFinite(d);
            radar.setLateral(t != null ? FcwdFrame.lateral(t) : 0);
            radar.updateTelemetry(speed, d, vrel, f.ttc, f.state, found, t != null ? t.score : 0, true,
                    String.format(Locale.US, "QNX · Hexagon DSP · %.0f мс · %.1f к/с", f.inferMs, f.fps));
            boxes.update(f);
            refreshStatus(f, vhal);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences prefs = getSharedPreferences(FcwdLinkService.PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111820);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Seres F515 ADAS · QNX");
        title.setTextSize(24); title.setTextColor(Color.WHITE); title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Предупреждение о сближении: нейросеть на DSP в QNX, здесь только отображение");
        sub.setTextSize(14); sub.setTextColor(0xFF94A3B8); sub.setGravity(Gravity.CENTER); sub.setPadding(0, 4, 0, 12);
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(14); status.setPadding(16, 10, 16, 10);
        root.addView(status);

        int h = getResources().getDisplayMetrics().heightPixels;
        radar = new AdasRadarView(this);
        radar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(360, Math.min(520, (int) (h * .42f)))));
        root.addView(card(radar));
        boxes = new CameraBoxesView(this);
        boxes.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(300, Math.min(480, (int) (h * .36f)))));
        root.addView(card(boxes));

        CheckBox sound = new CheckBox(this);
        sound.setText("Звук второго уровня · первый FCW (первый уровень без звука)");
        sound.setTextSize(15); sound.setTextColor(0xFFFBBF24);
        sound.setChecked(prefs.getBoolean("sound", true));
        sound.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("sound", on).apply();
            if (!on) NavigationAudioBuzzer.stop();
        });
        root.addView(sound);
        final CheckBox banner = new CheckBox(this);
        banner.setText("Баннер на приборке (применяется при запуске fcwd)");
        banner.setTextSize(15); banner.setTextColor(0xFFFFD54F);
        banner.setChecked(prefs.getBoolean("banner", true));
        banner.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("banner", on).apply());
        root.addView(banner);

        CheckBox autostart = new CheckBox(this);
        autostart.setText("Автозапуск ADAS при загрузке ГУ");
        autostart.setTextSize(15); autostart.setTextColor(0xFF86EFAC);
        autostart.setChecked(AutostartSettings.isEnabled(this));
        final boolean[] updatingAutostart = { false };
        autostart.setOnCheckedChangeListener((button, on) -> {
            if (updatingAutostart[0]) return;
            try {
                AutostartSettings.setEnabled(this, on);
                Toast.makeText(this, on ? "Автозапуск включён" : "Автозапуск выключен", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                updatingAutostart[0] = true;
                autostart.setChecked(AutostartSettings.isEnabled(this));
                updatingAutostart[0] = false;
                Toast.makeText(this, "Не удалось сохранить автозапуск", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(autostart);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        Button start = new Button(this);
        startButton = start;
        start.setText("Запустить fcwd на QNX");
        start.setOnClickListener(v -> qnxAction(true, banner.isChecked()));
        row.addView(start);
        Button stop = new Button(this);
        stopButton = stop;
        stop.setText("Остановить fcwd");
        stop.setOnClickListener(v -> qnxAction(false, false));
        row.addView(stop);
        root.addView(row);
        controlStatus = new TextView(this);
        controlStatus.setTextColor(0xFFCBD5E1); controlStatus.setTextSize(15);
        root.addView(controlStatus);

        calibration = new CalibrationPanel(this, b);
        root.addView(calibration);

        maintenance = new MaintenancePanel(this);
        root.addView(maintenance);

        setContentView(scroll);
        startForegroundService(new Intent(this, FcwdLinkService.class));
        refreshStatus(null, Float.NaN);
    }

    private LinearLayout card(android.view.View v) {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(16, 14, 16, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161E26); bg.setCornerRadius(16); bg.setStroke(2, 0xFF334155);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 12);
        c.setLayoutParams(lp);
        c.addView(v);
        return c;
    }

    private void qnxAction(final boolean start, final boolean banner) {
        startButton.setEnabled(false); stopButton.setEnabled(false);
        controlStatus.setText(start ? "Подготовка и запуск ADAS… При первой установке копирование может занять несколько минут." : "Остановка fcwd…");
        Toast.makeText(this, start ? "Запуск fcwd…" : "Остановка fcwd…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                QnxControl q = new QnxControl(this);
                if (start) q.start(banner); else q.stop();
                SystemClock.sleep(start ? 1500 : 500);
                msg = q.running() ? "fcwd работает" : "fcwd не запущен";
            } catch (Exception e) {
                msg = "QNX: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                startButton.setEnabled(true); stopButton.setEnabled(true);
                controlStatus.setText(m);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        }, "QnxAdasControl").start();
    }

    private void refreshStatus(FcwdFrame f, float vhal) {
        boolean fresh = f != null || SystemClock.uptimeMillis() - lastFrameAt < 1500;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(8);
        String s;
        if (f != null) {
            s = String.format(Locale.US, "Связь с fcwd · скорость PPS %s / VHAL %s км/ч",
                    f.speedPps >= 0 ? String.format(Locale.US, "%.0f", f.speedPps) : "—",
                    Float.isFinite(vhal) ? String.format(Locale.US, "%.0f", vhal) : "—");
            status.setTextColor(0xFF86EFAC); bg.setColor(0xFF14532D);
        } else if (linkUp && fresh) {
            s = "Связь с fcwd";
            status.setTextColor(0xFF86EFAC); bg.setColor(0xFF14532D);
        } else {
            s = "fcwd не подключён: запустите его кнопкой ниже";
            status.setTextColor(0xFFFDE68A); bg.setColor(0xFF78350F);
        }
        status.setText(s);
        status.setBackground(bg);
    }

    @Override protected void onResume() {
        super.onResume();
        if (maintenance != null) maintenance.refresh();
        if (calibration != null) calibration.refresh();
        IntentFilter fl = new IntentFilter(FcwdLinkService.ACTION_FRAME);
        fl.addAction(FcwdLinkService.ACTION_LINK);
        registerReceiver(receiver, fl);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (calibration != null) calibration.saveState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (calibration != null && calibration.result(request, result, data)) return;
        super.onActivityResult(request, result, data);
    }
}
