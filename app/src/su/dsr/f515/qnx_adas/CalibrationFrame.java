package su.dsr.f515.qnx_adas;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Validated native frame packet. No Android dependencies, to test dimensions and colour conversion. */
final class CalibrationFrame {
    final int width, height, stride, input, cropTop, modelWidth, modelHeight;
    final long sequence, timestampNs;
    final byte[] uyvy, modelRgb;

    private CalibrationFrame(ByteBuffer h, DataInputStream in, long total) throws IOException {
        width = h.getInt(); height = h.getInt(); stride = h.getInt();
        sequence = Integer.toUnsignedLong(h.getInt()); timestampNs = h.getLong();
        input = h.getInt(); cropTop = h.getInt(); modelWidth = h.getInt(); modelHeight = h.getInt();
        int rawSize = h.getInt(), modelSize = h.getInt(), flags = h.getInt(), reserved = h.getInt();
        if (width < 2 || width > 2048 || width % 2 != 0 || height < 1 || height > 2160
                || stride != width * 2 || cropTop < 0 || cropTop >= height
                || modelWidth < 1 || modelWidth > 2048 || modelHeight < 1 || modelHeight > 1024
                || input < 0 || input > 255 || timestampNs < 0 || flags != 1 || reserved != 0
                || rawSize != (long) stride * height || modelSize != (long) modelWidth * modelHeight * 3
                || total != 64L + rawSize + modelSize || total > CalibrationTransfer.MAX_BYTES)
            throw new IOException("Некорректный формат кадра камеры");
        uyvy = new byte[rawSize]; modelRgb = new byte[modelSize];
        in.readFully(uyvy); in.readFully(modelRgb);
        if (in.read() != -1) throw new IOException("Лишние данные после снимка");
    }

    static CalibrationFrame read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            byte[] header = new byte[64]; in.readFully(header);
            if (!Arrays.equals(Arrays.copyOf(header, 8), "F515CAL1".getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("Неизвестный формат снимка");
            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN); b.position(8);
            return new CalibrationFrame(b, in, file.length());
        }
    }

    void argbRow(int y, int[] out) {
        if (y < 0 || y >= height || out.length < width) throw new IllegalArgumentException();
        for (int x = 0; x < width; x++) {
            int pair = y * stride + (x / 2) * 4;
            int u = (uyvy[pair] & 255) - 128, v = (uyvy[pair + 2] & 255) - 128;
            int c = ((uyvy[y * stride + x * 2 + 1] & 255) - 16) * 1192;
            // Same BT.601 limited-range integer coefficients as native Preprocess.
            int r = clamp((c + 1634 * v + 512) >> 10);
            int g = clamp((c - 401 * u - 833 * v + 512) >> 10);
            int b = clamp((c + 2066 * u + 512) >> 10);
            out[x] = 0xff000000 | r << 16 | g << 8 | b;
        }
    }

    void writeModelPpm(OutputStream out) throws IOException {
        out.write(("P6\n" + modelWidth + " " + modelHeight + "\n255\n").getBytes(StandardCharsets.US_ASCII));
        out.write(modelRgb);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
}
