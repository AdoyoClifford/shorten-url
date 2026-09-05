package org.adoyo.shortenurl.service;

/** No link has ever had this code. Becomes a 404 at the edge. */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("no link with code: " + code);
    }
}
