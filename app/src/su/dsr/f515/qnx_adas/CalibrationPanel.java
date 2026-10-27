package su.dsr.f515.qnx_adas;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Capture, preview, save and share without granting access to all device storage. */
final class CalibrationPanel extends LinearLayout {
    private static final int SAVE_REQUEST = 5156;
    private static final AtomicBoolean CAPTURING = new AtomicBoolean();
    private final Activity activity;
    private final Button capture, save, share;
    private final TextView status;
    private final ImageView preview;
    private File current;
    private String savingName;
    private final Runnable completionCheck = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            if (!CAPTURING.get() && !capture.isEnabled()) refresh();
            postDelayed(this, 1000);
        }
    };

    CalibrationPanel(Activity activity, Bundle state) {
        super(activity); this.activity = activity;
        setOrientation(VERTICAL); setPadding(0, 20, 0, 16);
        capture = new Button(activity); capture.setText("Сделать снимок для калибровки");
        capture.setOnClickListener(v -> capture()); addView(capture);
        status = new TextView(activity); status.setTextColor(0xFFCBD5E1); status.setTextSize(14); addView(status);
        preview = new ImageView(activity); preview.setAdjustViewBounds(true);
        preview.setMaxHeight(360); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); addView(preview);
        LinearLayout row = new LinearLayout(activity);
        save = new Button(activity); save.setText(android.os.Build.VERSION.SDK_INT >= 29 ? "Сохранить в Загрузки" : "Сохранить ZIP…");
        save.setOnClickListener(v -> save()); row.addView(save);
        share = new Button(activity); share.setText("Поделиться…"); share.setOnClickListener(v -> share()); row.addView(share);
        addView(row);
        if (state != null) savingName = state.getString("calibration_saving");
        refresh();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(completionCheck, 1000); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(completionCheck); super.onDetachedFromWindow(); }

    void refresh() {
        capture.setEnabled(!CAPTURING.get());
        current = CalibrationArchive.latest(activity);
        save.setEnabled(current != null); share.setEnabled(current != null);
        if (CAPTURING.get()) status.setText("Получение кадра камеры и подготовка архива…");
        else if (current == null) status.setText("Полный кадр камеры, вход нейросети и параметры. Снимок сохраняется на ГУ.");
        else {
            status.setText("Снимок сохранён: " + current.getName() + "\nМожно сохранить ZIP в Загрузки или отправить.");
            final File file = current;
            new Thread(() -> {
                try {
                    android.graphics.Bitmap image = CalibrationArchive.preview(file);
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed() && file.equals(current)) preview.setImageBitmap(image);
                        else if (image != null) image.recycle();
                    });
                } catch (IOException ignored) { }
            }, "CalibrationPreview").start();
        }
    }

    private void capture() {
        if (!CAPTURING.compareAndSet(false, true)) return;
        capture.setEnabled(false); status.setText("Получение кадра камеры и подготовка архива… Дождитесь завершения.");
        final android.content.Context app = activity.getApplicationContext();
        new Thread(() -> {
            String failure = null;
            try { CalibrationArchive.capture(app, false); }
            catch (Exception e) { failure = "Снимок: " + e.getMessage(); }
            finally { CAPTURING.set(false); }
            final String error = failure;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                refresh(); if (error != null) status.setText(error);
            });
        }, "CalibrationCapture").start();
    }

    private void save() {
        if (current == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            final File archive = current;
            final android.content.Context app = activity.getApplicationContext();
            save.setEnabled(false); status.setText("Сохранение архива в Загрузки…");
            new Thread(() -> {
                String message;
                try { CalibrationArchive.saveToDownloads(app, archive); message = "Архив сохранён: Загрузки/F515AdasQnx"; }
                catch (Exception e) { message = "Не удалось сохранить архив: " + e.getMessage(); }
                final String text = message;
                activity.runOnUiThread(() -> { if (!activity.isDestroyed()) { save.setEnabled(true); status.setText(text); } });
            }, "CalibrationDownloads").start();
            return;
        }
        savingName = current.getName();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, savingName);
        try { activity.startActivityForResult(intent, SAVE_REQUEST); }
        catch (RuntimeException e) { status.setText("Выбор папки недоступен. Используйте «Поделиться…»."); }
    }

    private void share() {
        if (current == null) return;
        Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".calibration")
                .appendPath(current.getName()).build();
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(activity.getContentResolver(), current.getName(), uri));
        try { activity.startActivity(Intent.createChooser(intent, "Снимок для калибровки")); }
        catch (RuntimeException e) { status.setText("Нет приложения для отправки ZIP. Используйте «Сохранить ZIP…»."); }
    }

    void saveState(Bundle state) { state.putString("calibration_saving", savingName); }

    boolean result(int request, int result, Intent data) {
        if (request != SAVE_REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final String name = savingName; final Uri uri = data.getData();
        if (name == null || !CalibrationArchive.validName(name)) { status.setText("Не выбран снимок для сохранения"); return true; }
        final android.content.Context app = activity.getApplicationContext();
        status.setText("Сохранение архива…");
        new Thread(() -> {
            String message;
            try (InputStream in = new FileInputStream(new File(CalibrationArchive.directory(app), name));
                 OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Папка недоступна");
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush(); message = "Архив сохранён в выбранном месте";
            } catch (Exception e) { message = "Не удалось сохранить архив: " + e.getMessage(); }
            final String text = message;
            activity.runOnUiThread(() -> { if (!activity.isDestroyed()) status.setText(text); });
        }, "CalibrationExport").start();
        return true;
    }
}
