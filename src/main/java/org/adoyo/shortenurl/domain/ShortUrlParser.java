package org.adoyo.shortenurl.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognises our own short URLs, so a link to a link can be flattened (api-design.md 2.1).
 */
public final class ShortUrlParser {

    private final String baseHost;
    private final int basePort;

    public ShortUrlParser(String baseUrl) {
        URI base = URI.create(baseUrl);
        this.baseHost = hostOf(base);
        this.basePort = base.getPort();
    }

    public Optional<String> codeIn(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }

        if (!hostOf(uri).equals(baseHost) || uri.getPort() != basePort) {
            return Optional.empty();
        }

        String path = uri.getPath();
        if (path == null || path.length() < 2) {
            return Optional.empty();
        }

        String code = path.substring(1);
        return code.contains("/") ? Optional.empty() : Optional.of(code);
    }

    private static String hostOf(URI uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }
}
