package su.dsr.f515.qnx_adas;

import java.io.IOException;
import java.net.URL;

/** Values from the release feed are untrusted until checked. */
final class ReleaseMetadata {
    static final long MAX_APK = 64L * 1024 * 1024;
    final String version, notes, url, sha256;
    final long code, size;
    ReleaseMetadata(String version, long code, String notes, String url, long size, String sha256) throws IOException {
        if (version == null || !version.matches("[0-9]+(\\.[0-9]+){1,3}") || code <= 0
                || size <= 0 || size > MAX_APK || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IOException("Некорректные метаданные обновления");
        validateUrl(url);
        this.version = version; this.code = code; this.notes = notes;
        this.url = url; this.size = size; this.sha256 = sha256;
    }
    static URL validateUrl(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || !"tm.dsr.su".equals(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
                || !url.getPath().matches("/f515/adas-qnx/[A-Za-z0-9._-]+")
                || url.getQuery() != null || url.getRef() != null)
            throw new IOException("Недопустимый адрес обновления");
        return url;
    }
}
