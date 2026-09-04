package org.adoyo.shortenurl.api;

import java.time.Instant;

/**
 * Body of POST /api/v1/links (docs/api-design.md 2.1).
 *
 * <p>Every field is optional at this layer - the rules live in LinkService, so they apply however
 * a link is created rather than only when it arrives over HTTP.
 */
public record CreateLinkRequest(String url, String alias, Instant expiresAt, Long ttlSeconds) {
}
