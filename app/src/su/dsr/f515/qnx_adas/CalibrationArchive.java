package su.dsr.f515.qnx_adas;

import android.content.Context;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Local, lossless calibration bundle. No uploads or broad storage permissions. */
final class CalibrationArchive {
    static File directory(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "calibration");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог снимков");
        return dir;
    }

    static File latest(Context context) {
        try {
            File[] files = directory(context).listFiles(f -> validName(f.getName()) && f.isFile());
            if (files == null || files.length == 0) return null;
            Arrays.sort(files, Comparator.comparing(File::getName));
            return files[files.length - 1];
        } catch (IOException e) { return null; }
    }

    static boolean validName(String name) { return name.matches("F515-calibration-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}\\.zip"); }

    static File capture(Context context, boolean fallback) throws Exception {
        File packet = File.createTempFile("calibration-", ".bin", context.getCacheDir());
        try {
            long requestedAt = System.currentTimeMillis();
            new QnxControl(context).captureCalibration(packet, fallback);
            return create(context, packet, requestedAt);
        } finally { packet.delete(); }
    }

    static File create(Context context, File packet, long requestedAt) throws Exception {
        CalibrationFrame frame = CalibrationFrame.read(packet);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(requestedAt));
        File target = new File(directory(context), "F515-calibration-" + stamp + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".zip");
        File stage = new File(target.getPath() + ".part");
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
            int[] row = new int[frame.width];
            for (int y = 0; y < frame.height; y++) {
                frame.argbRow(y, row); bitmap.setPixels(row, 0, frame.width, 0, y, frame.width, 1);
            }
            QnxPayload payload;
            try (InputStream in = context.getAssets().open("qnx/manifest.properties")) { payload = QnxPayload.read(in); }
            JSONObject metadata = new JSONObject();
            metadata.put("format", "F515 calibration v1");
            metadata.put("request_time_unix_ms", requestedAt);
            metadata.put("archive_time_unix_ms", System.currentTimeMillis());
            metadata.put("camera_timestamp_ns", frame.timestampNs);
            metadata.put("camera_clock", "AIS monotonic; not UTC");
            metadata.put("camera_sequence", frame.sequence); metadata.put("camera_input", frame.input);
            metadata.put("width", frame.width); metadata.put("height", frame.height); metadata.put("stride", frame.stride);
            metadata.put("raw_format", "UYVY packed; BT.601 limited range");
            metadata.put("crop_top", frame.cropTop); metadata.put("crop_height", frame.height - frame.cropTop);
            metadata.put("model_width", frame.modelWidth); metadata.put("model_height", frame.modelHeight);
            metadata.put("model_format", "RGB uint8 HWC; divide by 255 for float input");
            metadata.put("resize", "native bilinear pixel centres; see preprocess.cpp");
            metadata.put("raw_sha256", hash(frame.uyvy)); metadata.put("model_rgb_sha256", hash(frame.modelRgb));
            metadata.put("packet_sha256", QnxPayload.hash(packet)); metadata.put("payload_id", payload.id());
            metadata.put("fcwd_sha256", payload.entries[0].sha256); metadata.put("dlc_sha256", payload.entries[1].sha256);
            metadata.put("app_version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
            metadata.put("reference_calibration", new JSONObject().put("K", FcwdFrame.K).put("horizon_y", FcwdFrame.Y_H)
                    .put("centre_x", FcwdFrame.X_C).put("camera_height_m", FcwdFrame.CAM_H));
            try (FileOutputStream file = new FileOutputStream(stage);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file))) {
                zip.setLevel(1);
                zip.putNextEntry(new ZipEntry("frame.png"));
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) throw new IOException("Не удалось сохранить PNG");
                zip.closeEntry();
                entry(zip, "frame.uyvy", frame.uyvy);
                zip.putNextEntry(new ZipEntry("input.ppm")); frame.writeModelPpm(zip); zip.closeEntry();
                entry(zip, "metadata.json", (metadata.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                entry(zip, "README.txt", ("F515 ADAS QNX — calibration snapshot\n"
                        + "frame.png: full camera frame, no crop/resize/overlays; lossless BT.601 RGB conversion.\n"
                        + "frame.uyvy: original packed camera pixels, without stride padding.\n"
                        + "input.ppm: exact RGB input produced by native preprocessing from the same frame.\n"
                        + "metadata.json: dimensions, crop, timestamps, hashes and reference calibration.\n"
                        + "Camera timestamp is monotonic; request_time_unix_ms is Android wall time before capture.\n"
                        + "Capturing does not modify calibration or restart ADAS. Files stay on this device until exported.\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.finish(); zip.flush(); file.getFD().sync();
            }
            if (!stage.renameTo(target)) throw new IOException("Не удалось завершить сохранение снимка");
            return target;
        } finally {
            if (bitmap != null) bitmap.recycle();
            stage.delete();
        }
    }

    static Bitmap preview(File archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry("frame.png");
            if (entry == null) throw new IOException("Нет изображения в архиве");
            try (InputStream in = zip.getInputStream(entry)) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
                return BitmapFactory.decodeStream(in, null, options);
            }
        }
    }

    /** Android 10+: own Downloads entry, without launching firmware-blocked document UI. */
    static Uri saveToDownloads(Context context, File archive) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, archive.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/F515AdasQnx");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Каталог Загрузки недоступен");
        boolean complete = false;
        try {
            try (InputStream in = new FileInputStream(archive);
                 OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Не удалось открыть файл в Загрузках");
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush();
            }
            ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, ready, null, null) != 1)
                throw new IOException("Не удалось завершить сохранение в Загрузки");
            complete = true;
            return uri;
        } finally {
            if (!complete) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static String hash(byte[] data) throws Exception {
        return QnxPayload.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }
}
