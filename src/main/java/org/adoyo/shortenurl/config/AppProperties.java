package org.adoyo.shortenurl.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings. Every knob documented in docs/api-design.md 11 lives here so that
 * behaviour is configured rather than branched on in code.
 */
@ConfigurationProperties("app")
public record AppProperties(

        /** Public origin used to build shortUrl in responses. No trailing slash. */
        @DefaultValue("http://localhost:8080") String baseUrl,

        /** Length of generated codes. 7 chars of base58 is ~2.2e12 values (api-design.md 5). */
        @DefaultValue("7") int codeLength,

        /** base58: base62 minus 0, O, I and l - the glyphs people transcribe wrongly. */
        @DefaultValue("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz") String codeAlphabet,

        /** Default lifetime when a create request does not specify one. Every link expires. */
        @DefaultValue("30") int defaultTtlDays,

        /** Shard count for the all-links-index partition key (api-design.md 8.1). */
        @DefaultValue("10") int listShards,

        /** Create missing tables at startup. Local dev only - real envs use IaC. */
        @DefaultValue("false") boolean bootstrapTables,

        @DefaultValue Tables tables,

        @DefaultValue Analytics analytics,

        @DefaultValue RateLimit ratelimit) {

    public record Tables(
            @DefaultValue("links") String links,
            @DefaultValue("clicks") String clicks) {

        public List<String> all() {
            return List.of(links, clicks);
        }
    }

    public record Analytics(
            /** How often buffered clicks are aggregated and written (api-design.md 7.1). */
            @DefaultValue("2s") Duration flushInterval,
            /** Bounded queue: when full, clicks are dropped so redirects never block. */
            @DefaultValue("10000") int queueCapacity) {
    }

    public record RateLimit(
            @DefaultValue("60") int createsPerMinute) {
    }
}
