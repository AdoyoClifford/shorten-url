package org.adoyo.shortenurl.api;

import java.time.Instant;

import org.adoyo.shortenurl.domain.Link;

/** A link as the API renders it (docs/api-design.md 1). */
public record LinkResponse(
        String code,
        String shortUrl,
        String url,
        boolean custom,
        Instant createdAt,
        Instant expiresAt,
        String status) {

    public static LinkResponse of(Link link, String baseUrl, Instant now) {
        return new LinkResponse(
                link.code(),
                baseUrl + "/" + link.code(),
                link.targetUrl(),
                link.custom(),
                link.createdAt(),
                link.expiresAt(),
                link.status(now).name());
    }
}
