package org.adoyo.shortenurl.domain;

import java.time.Instant;

/**
 * A shortened link. Immutable - every change produces a new instance.
 *
 * <p>Pure domain: no AWS types, no Spring, no JSON. The storage-only attributes from
 * docs/api-design.md 8.1 (listPk) and the wire-only ones (shortUrl) deliberately do not live here.
 * They are added at the edges, so this class can be tested with nothing running.
 *
 * @param code       the short code, e.g. "aX9k2Qp" or a custom alias. Never null or blank.
 * @param targetUrl  where the redirect sends people. Never null or blank. Already flattened
 *                   (api-design.md 2.1), so it never points back at us.
 * @param createdAt  never null.
 * @param expiresAt  never null - every link expires (api-design.md 4).
 * @param deletedAt  null means "not deleted". Soft delete only, and reversible by renewal.
 * @param custom     true if the code came from a user-supplied alias rather than the generator.
 */
public record Link(
        String code,
        String targetUrl,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt,
        boolean custom) {

    public Link {
        // TODO Reject a null/blank code, a null/blank targetUrl, a null createdAt and a null
        //      expiresAt. deletedAt is legitimately null, so leave it alone.
        //      This is a compact constructor: assign nothing, just validate the parameters.
        //      IllegalArgumentException is the right failure - a Link that violates this should be
        //      impossible to construct, not merely discouraged.
        if(code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code is required");
        }

        if(targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("Target URL is required");
        }

        if(createdAt == null) {
            throw new IllegalArgumentException("Created at is required");
        }

        if(expiresAt == null) {
            throw new IllegalArgumentException("Expires at is required");
        }
    }

    /**
     * What this link is, as of {@code now}.
     *
     * <p>Rules, from api-design.md 4:
     * <ul>
     *   <li>deleted beats everything - a deleted link is DELETED even if it also expired</li>
     *   <li>expired means {@code expiresAt <= now}. Exactly at expiresAt it is already EXPIRED</li>
     *   <li>otherwise ACTIVE</li>
     * </ul>
     */
    public LinkStatus status(Instant now) {

        if(deletedAt != null) {
            return LinkStatus.DELETED;
        }

        if(!expiresAt.isAfter(now)) {
            return LinkStatus.EXPIRED;
        }

        return LinkStatus.ACTIVE;
    }

    /**
     * Whether the redirect may follow this link, as of {@code now}.
     *
     * <p>Only an ACTIVE link resolves. Everything else is 410 (api-design.md 4) - which is why this
     * is a question about ACTIVE specifically, not about "not deleted".
     */
    public boolean isResolvable(Instant now) {
        return status(now) == LinkStatus.ACTIVE;
    }

    /**
     * A copy expiring at {@code newExpiresAt}, for PATCH (api-design.md 2.4).
     *
     * <p>Renewal, not just an edit: an expired or soft-deleted link coming back must be ACTIVE
     * again, so this also clears {@code deletedAt}. Same code, same target, same history.
     *
     * <p>{@code newExpiresAt} may not be null - there is no such thing as a link that never
     * expires. Reject it the same way the constructor does.
     *
     * <p>Must not mutate this instance - that is the point of a record.
     */
    public Link renewedUntil(Instant newExpiresAt) {
        return new Link(code, targetUrl, createdAt, newExpiresAt, null, custom);
    }

    /**
     * A copy marked deleted at {@code now}, for DELETE (api-design.md 2.5) and for the sweeper that
     * retires expired links.
     *
     * <p>Soft delete: nothing is removed and the link can be renewed later. Deleting an
     * already-deleted link must keep the original deletedAt - DELETE is idempotent, so a second
     * call must not quietly rewrite when the first one happened.
     */
    public Link asDeleted(Instant now) {
        return deletedAt != null ? this : new Link(code, targetUrl,createdAt, expiresAt, now, custom);
    }
}
