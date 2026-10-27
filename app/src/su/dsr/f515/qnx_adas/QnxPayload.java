package su.dsr.f515.qnx_adas;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

/** Strict deterministic manifest, also exercised by host-side regression tests. */
final class QnxPayload {
    final Entry[] entries;
    private QnxPayload(Entry[] entries) { this.entries = entries; }

    static QnxPayload read(InputStream input) throws IOException {
        Properties p = new Properties(); p.load(input);
        if (p.size() != 2) throw new IOException("QNX manifest must contain exactly binary and model");
        return new QnxPayload(new Entry[] {
            new Entry("fcwd", "755", p.getProperty("fcwd")),
            new Entry("autospeed_heads3o_int8.dlc", "644", p.getProperty("autospeed_heads3o_int8.dlc"))
        });
    }

    String id() throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Entry e : entries) canonical.append(e.name).append(':').append(e.size).append(':')
                .append(e.mode).append(':').append(e.sha256).append('\n');
        return hex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    String probe(String directory) {
        StringBuilder script = new StringBuilder("ok=0\nif [ -d ").append(directory)
                .append(" ] && [ ! -L ").append(directory).append(" ]; then\nok=1\n");
        for (Entry e : entries) {
            String path = directory + "/" + e.name;
            script.append("p=").append(path).append('\n');
            script.append("if ").append(e.check("$p")).append("; then chmod ").append(e.mode)
                    .append(" $p && echo ENTRY_OK_").append(e.name).append(" || ok=0; else ok=0; fi\n");
        }
        script.append("fi\n");
        return script.append("if [ \"$ok\" = 1 ]; then echo PAYLOAD_OK; fi\necho PAYLOAD_CHECKED").toString();
    }

    static final class Entry {
        final String name, mode, sha256;
        final long size;
        Entry(String name, String mode, String spec) throws IOException {
            this.name = name; this.mode = mode;
            String[] fields = spec == null ? new String[0] : spec.split(",", -1);
            if (fields.length != 3 || !mode.equals(fields[1]) || !fields[2].matches("[0-9a-f]{64}"))
                throw new IOException("Invalid QNX manifest entry: " + name);
            try { size = Long.parseLong(fields[0]); } catch (NumberFormatException e) { throw new IOException(e); }
            if (size <= 0 || size > 64 * 1024 * 1024) throw new IOException("Invalid QNX asset size");
            sha256 = fields[2];
        }
        String check(String path) {
            return "[ -f " + path + " ] && [ ! -L " + path + " ] && [ \"$(wc -c < " + path + ")\" -eq " + size
                    + " ] && openssl dgst -sha256 " + path + " 2>/dev/null | grep -q '= " + sha256 + "$'";
        }
        void verify(File file) throws Exception {
            if (file.length() != size || !sha256.equals(hash(file))) throw new IOException("QNX asset mismatch: " + name);
        }
    }

    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
