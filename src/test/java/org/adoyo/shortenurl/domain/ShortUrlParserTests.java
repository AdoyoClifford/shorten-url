package org.adoyo.shortenurl.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recognising our own short URLs - the input to flattening (docs/api-design.md 2.1).
 */
class ShortUrlParserTests {

    private static final ShortUrlParser PARSER = new ShortUrlParser("https://sho.rt");

    @Test
    void findsTheCodeInOneOfOurUrls() {
        assertThat(PARSER.codeIn("https://sho.rt/aX9k2Qp")).contains("aX9k2Qp");
    }

    @Test
    void ignoresSchemeAndHostCasing() {
        assertThat(PARSER.codeIn("http://SHO.RT/aX9k2Qp")).contains("aX9k2Qp");
    }

    @Test
    void ignoresQueryAndFragment() {
        assertThat(PARSER.codeIn("https://sho.rt/aX9k2Qp?x=1#y")).contains("aX9k2Qp");
    }

    @Test
    void findsNothingInSomebodyElsesUrl() {
        assertThat(PARSER.codeIn("https://example.com/aX9k2Qp")).isEmpty();
        assertThat(PARSER.codeIn("https://notsho.rt/aX9k2Qp")).isEmpty();
    }

    @Test
    void findsNothingWhenThereIsNoSingleCodeSegment() {
        assertThat(PARSER.codeIn("https://sho.rt")).isEmpty();
        assertThat(PARSER.codeIn("https://sho.rt/")).isEmpty();
        assertThat(PARSER.codeIn("https://sho.rt/api/v1/links")).isEmpty();
    }

    @Test
    void findsNothingInGarbage() {
        assertThat(PARSER.codeIn("not a url at all")).isEmpty();
        assertThat(PARSER.codeIn(null)).isEmpty();
        assertThat(PARSER.codeIn("")).isEmpty();
    }

    @Test
    void respectsThePortOfTheBaseUrl() {
        ShortUrlParser local = new ShortUrlParser("http://localhost:8080");

        assertThat(local.codeIn("http://localhost:8080/abc")).contains("abc");
        assertThat(local.codeIn("http://localhost:9090/abc")).isEmpty();
    }
}
