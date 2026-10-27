package su.dsr.f515.qnx_adas;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class PayloadReleaseTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    static void rejects(Action a) throws Exception {
        boolean rejected = false; try { a.run(); } catch (IOException e) { rejected = true; } check(rejected);
    }
    static QnxPayload parse(String text) throws Exception {
        return QnxPayload.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    static String shell(String command) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = p.getInputStream().read(b)) != -1) out.write(b, 0, n);
        check(p.waitFor() == 0); return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("qnx-payload-test-");
        try {
            Path binary = dir.resolve("fcwd"), model = dir.resolve("autospeed_heads3o_int8.dlc");
            Files.write(binary, new byte[]{1,2,3}); Files.write(model, new byte[]{4,5,6});
            String manifest = "fcwd=3,755," + QnxPayload.hash(binary.toFile()) + "\nautospeed_heads3o_int8.dlc=3,644," + QnxPayload.hash(model.toFile()) + "\n";
            QnxPayload payload = parse(manifest);
            check(payload.id().equals(parse(manifest).id()));
            for (QnxPayload.Entry e : payload.entries) e.verify(dir.resolve(e.name).toFile());
            check(shell(payload.probe(dir.toString())).contains("PAYLOAD_OK"));
            check(Files.isExecutable(binary));
            Path linked = dir.resolve("linked");
            Files.createSymbolicLink(linked, dir);
            check(!shell(payload.probe(linked.toString())).contains("PAYLOAD_OK"));
            Files.delete(linked);
            Files.write(binary, new byte[]{9,2,3}); // Same size, different binary: original regression.
            rejects(() -> payload.entries[0].verify(binary.toFile()));
            check(!shell(payload.probe(dir.toString())).contains("PAYLOAD_OK"));
            check(!payload.id().equals(parse(manifest.replace(payload.entries[0].sha256, QnxPayload.hash(binary.toFile()))).id()));
            Files.delete(model);
            check(!shell(payload.probe(dir.toString())).contains("PAYLOAD_OK"));
            rejects(() -> parse(""));
            rejects(() -> parse(manifest.replace(",755,", ",777,")));
            rejects(() -> parse(manifest.replace("fcwd=3", "fcwd=-1")));
            rejects(() -> parse(manifest.replace("fcwd=3", "fcwd=99999999999")));
            rejects(() -> parse(manifest.replace(",755," + payload.entries[0].sha256, ",755")));
            rejects(() -> parse(manifest + "../extra=1,644,invalid\n"));
            String url = "https://tm.dsr.su/f515/adas-qnx/F515AdasQnx-0.1.4.apk";
            new ReleaseMetadata("0.1.4", 5, "", url, 100, payload.entries[0].sha256);
            for (String bad : new String[]{"http://tm.dsr.su/f515/adas-qnx/a.apk", "https://evil.test/a.apk",
                    "https://tm.dsr.su@evil.test/f515/adas-qnx/a.apk", "https://tm.dsr.su/f515/adas-qnx/../a.apk",
                    "https://tm.dsr.su/f515/adas-qnx/%2e%2e/a.apk", "https://tm.dsr.su:444/f515/adas-qnx/a.apk",
                    "https://tm.dsr.su/f515/adas-qnx/a.apk?q=1"}) rejects(() -> ReleaseMetadata.validateUrl(bad));
            rejects(() -> new ReleaseMetadata("0.1.4", 0, "", url, 100, payload.entries[0].sha256));
            rejects(() -> new ReleaseMetadata("0.1.4", 5, "", url, 0, payload.entries[0].sha256));
            rejects(() -> new ReleaseMetadata("0.1.4", 5, "", url, 100, "invalid"));
            System.out.println("PASS payload/update validation: " + checks + " checks");
        } finally {
            Files.deleteIfExists(dir.resolve("fcwd")); Files.deleteIfExists(dir.resolve("autospeed_heads3o_int8.dlc")); Files.delete(dir);
        }
    }
}
