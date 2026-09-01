package org.adoyo.shortenurl.domain;

/**
 * What a link currently is, derived at read time - never stored.
 *
 * <p>See docs/api-design.md 1 and 4. Storing this would mean a background job has to keep it
 * truthful, and the moment that job is late the API starts lying.
 */
public enum LinkStatus {

    /** Resolvable. The redirect returns 302. */
    ACTIVE,

    /** Past its expiresAt. The redirect returns 410 or 404 depending on the grace window. */
    EXPIRED,

    /** Soft-deleted. The redirect returns 404, and the code is never reissued. */
    DELETED
}
