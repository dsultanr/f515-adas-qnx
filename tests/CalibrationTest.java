package su.dsr.f515.qnx_adas;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class CalibrationTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    static void rejects(Action action) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected);
    }
    static byte[] packet() {
        ByteBuffer b = ByteBuffer.allocate(86).order(ByteOrder.LITTLE_ENDIAN);
        b.put("F515CAL1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(4).putInt(2).putInt(8).putInt(-1).putLong(12345678965L);
        b.putInt(4).putInt(0).putInt(2).putInt(1).putInt(16).putInt(6).putInt(1).putInt(0);
        b.put(new byte[] {(byte)128,16,(byte)128,(byte)235,90,82,(byte)240,82,
                         (byte)128,16,(byte)128,(byte)235,90,82,(byte)240,82});
        b.put(new byte[] {1,2,3,4,5,6}); return b.array();
    }
    static CalibrationFrame read(byte[] bytes) throws Exception {
        Path temp = Files.createTempFile("calibration-test", ".bin");
        try { Files.write(temp, bytes); return CalibrationFrame.read(temp.toFile()); }
        finally { Files.delete(temp); }
    }
    static final String ID = "0123456789abcdef0123456789abcdef";
    static String hash(byte[] data) throws Exception { return QnxPayload.hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    static void feed(CalibrationTransfer transfer, byte[] data) throws Exception {
        check(!transfer.onLine("SNAP_BEGIN_" + ID));
        String encoded = Base64.getEncoder().encodeToString(data);
        for (int i = 0; i < encoded.length(); i += 64) transfer.onLine(encoded.substring(i, Math.min(i + 64, encoded.length())));
    }
    public static void main(String[] args) throws Exception {
        CalibrationFrame f = read(packet());
        check(f.width == 4 && f.height == 2 && f.stride == 8);
        check(f.sequence == 4294967295L && f.timestampNs == 12345678965L);
        int[] row = new int[4]; f.argbRow(0,row);
        check(row[0] == 0xff000000 && row[1] == 0xffffffff);
        check(((row[2] >> 16) & 255) >= 254 && ((row[2] >> 8) & 255) <= 2 && (row[2] & 255) <= 2);
        ByteArrayOutputStream ppm = new ByteArrayOutputStream(); f.writeModelPpm(ppm);
        check(Arrays.equals(ppm.toByteArray(), new byte[] {'P','6','\n','2',' ','1','\n','2','5','5','\n',1,2,3,4,5,6}));
        for (int offset : new int[] {8,12,16,32,36,40,44,48,52,56,60}) {
            byte[] bad = packet(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, Integer.MAX_VALUE);
            rejects(() -> read(bad));
        }
        byte[] magic = packet(); magic[0] = 0; rejects(() -> read(magic));
        rejects(() -> read(Arrays.copyOf(packet(),63)));
        rejects(() -> read(Arrays.copyOf(packet(),85)));
        rejects(() -> read(Arrays.copyOf(packet(),87)));
        byte[] bytes = new byte[131073]; new Random(42).nextBytes(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CalibrationTransfer transfer = new CalibrationTransfer(ID,bytes.length,hash(bytes),out);
        check(!transfer.onLine("echo SNAP_BEGIN_" + ID));
        feed(transfer,bytes); check(transfer.onLine("SNAP_END_"+ID)); transfer.requireComplete();
        check(Arrays.equals(bytes,out.toByteArray()));
        CalibrationTransfer truncated = new CalibrationTransfer(ID,bytes.length,hash(bytes),new ByteArrayOutputStream());
        feed(truncated,bytes); rejects(truncated::requireComplete);
        CalibrationTransfer corrupt = new CalibrationTransfer(ID,bytes.length,hash(new byte[bytes.length]),new ByteArrayOutputStream());
        feed(corrupt,bytes); rejects(() -> corrupt.onLine("SNAP_END_"+ID));
        CalibrationTransfer over = new CalibrationTransfer(ID,64,hash(bytes),new ByteArrayOutputStream());
        rejects(() -> feed(over,bytes));
        CalibrationTransfer invalid = new CalibrationTransfer(ID,64,hash(bytes),new ByteArrayOutputStream());
        invalid.onLine("SNAP_BEGIN_"+ID); rejects(() -> invalid.onLine("not base64"));
        rejects(() -> new CalibrationTransfer(ID,CalibrationTransfer.MAX_BYTES+1,hash(bytes),out));
        System.out.println("PASS calibration packet/colour/stream validation: " + checks + " checks");
    }
}
