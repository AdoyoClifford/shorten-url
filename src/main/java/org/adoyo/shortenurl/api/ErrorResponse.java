package org.adoyo.shortenurl.api;

/** Plain JSON errors (docs/api-design.md 3) - the status code carries the meaning. */
public record ErrorResponse(String error) {
}
