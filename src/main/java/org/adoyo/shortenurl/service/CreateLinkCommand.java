package org.adoyo.shortenurl.service;

import java.time.Instant;

/**
 * What the caller asked for. `expiresAt` and `ttlSeconds` are mutually exclusive; both may be null.
 */
public record CreateLinkCommand(
        String url,
        String alias,
        Instant expiresAt,
        Long ttlSeconds) {
}
