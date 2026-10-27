package su.dsr.f515.qnx_adas;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Base64;

/** Bounded streaming decoder: terminal echo is ignored outside the unique frame markers. */
final class CalibrationTransfer implements AdbClient.LineReader {
    static final long MAX_BYTES = 16 * 1024 * 1024;
    private final String begin, end, sha;
    private final long expected;
    private final OutputStream out;
    private final MessageDigest digest;
    private long received;
    private boolean started, finished;

    CalibrationTransfer(String id, long size, String sha, OutputStream out) throws Exception {
        if (!id.matches("[a-f0-9]{32}") || size < 64 || size > MAX_BYTES || !sha.matches("[a-f0-9]{64}"))
            throw new IOException("Некорректные параметры снимка");
        begin = "SNAP_BEGIN_" + id; end = "SNAP_END_" + id;
        this.expected = size; this.sha = sha; this.out = out;
        digest = MessageDigest.getInstance("SHA-256");
    }

    @Override public boolean onLine(String line) throws IOException {
        if (finished) return true;
        if (!started) { if (line.equals(begin)) started = true; return false; }
        if (line.equals(end)) {
            if (received != expected || !sha.equals(QnxPayload.hex(digest.digest())))
                throw new IOException("Снимок передан не полностью или повреждён");
            out.flush(); finished = true; return true;
        }
        if (line.isEmpty()) return false;
        if (line.length() > 128 || !line.matches("[A-Za-z0-9+/]+={0,2}") || line.length() % 4 != 0)
            throw new IOException("Повреждён поток снимка");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(line); }
        catch (IllegalArgumentException e) { throw new IOException("Некорректный base64 снимка", e); }
        if (received + bytes.length > expected) throw new IOException("Снимок превышает ожидаемый размер");
        out.write(bytes); digest.update(bytes); received += bytes.length;
        return false;
    }

    void requireComplete() throws IOException {
        if (!finished) throw new IOException("Передача снимка прервана");
    }
}
