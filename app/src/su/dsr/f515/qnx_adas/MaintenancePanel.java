package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicit update/permission actions, keeping the warning display independent. */
final class MaintenancePanel extends LinearLayout {
    private final Activity activity;
    private final Context app;
    private final SharedPreferences prefs;
    private final TextView permissions, update;
    private final Button grant, check, install;
    private ReleaseMetadata release;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (UpdateManager.STATUS.equals(key)) refresh();
    };

    MaintenancePanel(Activity activity) {
        super(activity); this.activity = activity; app = activity.getApplicationContext();
        prefs = app.getSharedPreferences(FcwdLinkService.PREFS, Context.MODE_PRIVATE);
        setOrientation(VERTICAL); setPadding(0, 16, 0, 0);
        TextView title = label("Приложение " + UpdateManager.version(app) + " · права и обновления");
        title.setTextSize(18); addView(title);
        permissions = label(""); addView(permissions);
        grant = button("Проверить и выдать права"); addView(grant);
        grant.setOnClickListener(v -> grant());
        Button repair = button("Проверить и восстановить компоненты QNX"); addView(repair);
        TextView repairStatus = label(""); addView(repairStatus);
        repair.setOnClickListener(v -> {
            repair.setEnabled(false); repairStatus.setText("Проверка и восстановление компонентов…");
            new Thread(() -> {
                try {
                    new QnxControl(app).prepare();
                    ui(() -> repairStatus.setText("Компоненты QNX проверены и готовы. Применяются при следующем запуске fcwd."));
                } catch (Exception e) { ui(() -> repairStatus.setText("Не удалось восстановить компоненты: " + e.getMessage())); }
                finally { ui(() -> repair.setEnabled(true)); }
            }, "AdasRepair").start();
        });
        update = label(""); addView(update);
        check = button("Проверить обновление · tm.dsr.su"); addView(check);
        check.setOnClickListener(v -> check());
        install = button("Скачать и установить обновление"); addView(install);
        install.setVisibility(GONE);
        install.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("Обновление " + release.version)
                .setMessage(release.notes + "\n\nСкачать и установить эту версию?")
                .setNegativeButton("Отмена", null).setPositiveButton("Установить", (d, which) -> install()).show());
        refresh();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(listener); refresh(); }
    @Override protected void onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onDetachedFromWindow(); }
    void refresh() {
        PermissionHelper helper = new PermissionHelper(app);
        permissions.setText(helper.status());
        if (prefs.getBoolean(PermissionHelper.PENDING, false) && helper.canInstall()) {
            prefs.edit().remove(PermissionHelper.PENDING).apply();
            UpdateManager.status(app, "Разрешение на обновления выдано. Можно проверить новую версию.");
        }
        update.setText(prefs.getString(UpdateManager.STATUS, "Обновления загружаются с tm.dsr.su по вашей команде."));
        check.setEnabled(!UpdateManager.BUSY.get()); install.setEnabled(!UpdateManager.BUSY.get());
    }
    private TextView label(String value) {
        TextView t = new TextView(activity); t.setText(value); t.setTextColor(0xFFCBD5E1); t.setTextSize(15); t.setPadding(0, 8, 0, 8); return t;
    }
    private Button button(String text) { Button b = new Button(activity); b.setText(text); return b; }
    private void ui(Runnable task) { activity.runOnUiThread(() -> { if (!activity.isDestroyed() && !activity.isFinishing()) task.run(); }); }
    private void grant() {
        grant.setEnabled(false); permissions.setText("Проверка доступа к ГУ…");
        new Thread(() -> {
            try {
                String result = new PermissionHelper(app).grant();
                ui(() -> permissions.setText(result));
            } catch (Exception e) {
                ui(() -> {
                    permissions.setText(e.getMessage() + "\n" + new PermissionHelper(app).status());
                    new AlertDialog.Builder(activity).setTitle("Разрешения приложения")
                            .setMessage("Локальная настройка не завершена. Можно открыть системные настройки.")
                            .setPositiveButton("Установка APK", (d, w) -> settings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                            .setNeutralButton("Работа в фоне", (d, w) -> settings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
                            .setNegativeButton("Закрыть", null).show();
                });
            } finally { ui(() -> grant.setEnabled(true)); }
        }, "AdasPermissions").start();
    }
    private void settings(String action) {
        try { activity.startActivity(new Intent(action, Uri.parse("package:" + app.getPackageName()))); }
        catch (Exception e) { permissions.setText("В этой прошивке нет такого экрана настроек. Нужен доступ к локальному ADB."); }
    }
    private void check() {
        if (!UpdateManager.BUSY.compareAndSet(false, true)) return;
        release = null; install.setVisibility(GONE);
        UpdateManager.status(app, "Проверка обновлений…"); refresh();
        new Thread(() -> {
            try {
                ReleaseMetadata found = UpdateManager.check();
                boolean newer = found.code > UpdateManager.installed(app).versionCode;
                UpdateManager.status(app, newer ? "Доступна версия " + found.version + "\n" + found.notes : "Установлена актуальная версия " + UpdateManager.version(app));
                ui(() -> { release = newer ? found : null; install.setVisibility(newer ? VISIBLE : GONE); });
            } catch (Exception e) { UpdateManager.status(app, "Не удалось проверить обновления: " + e.getMessage()); }
            finally { UpdateManager.BUSY.set(false); ui(this::refresh); }
        }, "AdasUpdateCheck").start();
    }
    private void install() {
        final ReleaseMetadata selected = release;
        if (selected == null || !UpdateManager.BUSY.compareAndSet(false, true)) return;
        refresh();
        new Thread(() -> {
            try { UpdateManager.downloadAndInstall(app, selected, message -> UpdateManager.status(app, message)); }
            catch (Exception e) { UpdateManager.status(app, "Обновление не установлено: " + e.getMessage()); }
            finally { UpdateManager.BUSY.set(false); ui(this::refresh); }
        }, "AdasUpdateInstall").start();
    }
}
