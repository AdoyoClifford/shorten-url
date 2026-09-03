package org.adoyo.shortenurl.service;

/**
 * A custom alias that somebody already owns. Becomes a 409 at the edge.
 */
public class CodeTakenException extends RuntimeException {

    public CodeTakenException(String code) {
        super("code is already taken: " + code);
    }
}
