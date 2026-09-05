package org.adoyo.shortenurl.api;

import java.time.Instant;

/** Body of PATCH /api/v1/links/{code}. Expiry is the only mutable field (api-design.md 2.4). */
public record UpdateLinkRequest(Instant expiresAt) {
}
