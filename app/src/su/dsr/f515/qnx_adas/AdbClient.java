package su.dsr.f515.qnx_adas;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client speaking the classic (pre-TLS) protocol to run shell commands
 * against local adbd (127.0.0.1:5555). Runs as root on this Seres head unit.
 */
public class AdbClient implements Closeable {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int MAXDATA = 256 * 1024;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    public AdbClient(String host, int port, byte[] pkcs8Key, byte[] adbPubKey, int timeoutMs)
            throws Exception {
        sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            out = new BufferedOutputStream(sock.getOutputStream());
            handshake(pkcs8Key, adbPubKey);
        } catch (Exception e) { close(); throw e; }
    }

    private static class Message {
        int command, arg0, arg1;
        byte[] data = new byte[0];
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xff);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1)
         .putInt(data.length).putInt(checksum).putInt(command ^ 0xffffffff);
        out.write(h.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private Message recv() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int length = h.getInt();
        int checksum = h.getInt();
        int magic = h.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException("bad adb magic");
        if (length < 0 || length > MAXDATA) throw new IOException("bad adb payload length " + length);
        if (length > 0) {
            m.data = new byte[length];
            in.readFully(m.data);
        }
        int actual = 0;
        for (byte b : m.data) actual += b & 0xff;
        if (actual != checksum) throw new IOException("bad adb checksum");
        return m;
    }

    private void handshake(byte[] pkcs8Key, byte[] adbPubKey) throws Exception {
        send(A_CNXN, A_VERSION, MAXDATA, "host::qnx_adas\0".getBytes("UTF-8"));
        PrivateKey key = loadKey(pkcs8Key);
        int signaturesSent = 0;
        for (int i = 0; i < 8; i++) {
            Message m = recv();
            if (m.command == A_CNXN) return;
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (signaturesSent == 0) {
                send(A_AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                signaturesSent++;
            } else {
                byte[] keyBytes = adbPubKey;
                if (keyBytes.length == 0 || keyBytes[keyBytes.length - 1] != 0)
                    keyBytes = java.util.Arrays.copyOf(keyBytes, keyBytes.length + 1);
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, keyBytes);
            }
        }
        throw new IOException("adb auth failed");
    }

    private static PrivateKey loadKey(byte[] pem) throws Exception {
        String text = new String(pem, "UTF-8")
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = android.util.Base64.decode(text, android.util.Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] payload = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, payload, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, payload, SHA1_DIGEST_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(payload);
    }

    public String shell(String command) throws IOException {
        return shell(command, null);
    }

    /** Finish a dedicated transfer connection once the remote shell confirms completion. */
    String shellUntil(String command, String marker) throws IOException {
        return shell(command, marker);
    }

    private String shell(String command, String terminalMarker) throws IOException {
        int localId = nextLocalId++;
        int remoteId = 0;
        send(A_OPEN, localId, 0, ("shell:" + command + '\0').getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            while (true) {
                Message m = recv();
                if (m.arg1 != localId && m.command != A_CLSE) continue;
                if (m.command == A_OKAY) {
                    remoteId = m.arg0;
                } else if (m.command == A_WRTE) {
                    remoteId = m.arg0;
                    sb.append(new String(m.data, "UTF-8"));
                    if (terminalMarker == null && sb.length() > 1024 * 1024) throw new IOException("adb output too large");
                    // Telnet can echo the model even after stty -echo. A transfer only needs
                    // its completion marker, so retain a rolling tail instead of megabytes.
                    if (terminalMarker != null && sb.length() > 65536) sb.delete(0, sb.length() - 8192);
                    send(A_OKAY, localId, remoteId, null);
                    if (terminalMarker != null) {
                        for (String line : sb.toString().split("\n")) {
                            if (terminalMarker.equals(line.trim())) {
                                send(A_CLSE, localId, remoteId, null);
                                close();
                                return sb.toString();
                            }
                        }
                    }
                } else if (m.command == A_CLSE) {
                    if (m.arg1 != localId) continue;
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            close();
            throw e;
        }
        return sb.toString();
    }

    public interface LineListener {
        void onLine(String line);
    }

    /** A dedicated connection for a long-running, newline-delimited read-only probe. */
    public void shellLines(String command, LineListener listener) throws IOException {
        shellLinesUntil(command, line -> { listener.onLine(line); return false; });
    }

    public interface LineReader {
        /** Return true to finish this dedicated connection after a complete verified response. */
        boolean onLine(String line) throws IOException;
    }

    public void shellLinesUntil(String command, LineReader listener) throws IOException {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, ("shell:" + command + '\0').getBytes("UTF-8"));
        StringBuilder pending = new StringBuilder();
        while (!Thread.currentThread().isInterrupted()) {
            Message m = recv();
            if (m.arg1 != localId) continue;
            if (m.command == A_WRTE) {
                pending.append(new String(m.data, "UTF-8"));
                send(A_OKAY, localId, m.arg0, null);
                int end;
                while ((end = pending.indexOf("\n")) >= 0) {
                    if (listener.onLine(pending.substring(0, end).trim())) {
                        send(A_CLSE, localId, m.arg0, null);
                        close();
                        return;
                    }
                    pending.delete(0, end + 1);
                }
                if (pending.length() > 4096) throw new IOException("Probe line too long");
            } else if (m.command == A_CLSE) {
                return;
            }
        }
    }

    @Override
    public void close() {
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }
}
