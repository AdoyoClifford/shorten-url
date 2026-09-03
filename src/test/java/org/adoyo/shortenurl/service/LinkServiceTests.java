package org.adoyo.shortenurl.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.domain.ShortUrlParser;
import org.adoyo.shortenurl.persistence.LinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Creating links: docs/api-design.md 2.1 and 4.
 *
 * <p>Runs against a real repository - the interesting parts (a taken alias, a code collision) are
 * decided by DynamoDB's conditional put, so faking the store would fake the behaviour under test.
 * The clock and the code supplier are injected, so everything else stays deterministic.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LinkServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    @Autowired
    LinkRepository repository;

    private static String newCode() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private LinkService service(String... codes) {
        Deque<String> queue = new ArrayDeque<>(List.of(codes));
        Supplier<String> supplier = () -> queue.isEmpty() ? newCode() : queue.poll();
        return new LinkService(repository, supplier, new ShortUrlParser("https://sho.rt"),
                Clock.fixed(NOW, ZoneOffset.UTC), DEFAULT_TTL, 3);
    }

    private LinkService service() {
        return service(newCode());
    }

    @Nested
    @DisplayName("codes")
    class Codes {

        @Test
        void generatesACodeWhenNoAliasIsGiven() {
            String code = newCode();

            Link created = service(code).create(new CreateLinkCommand("https://example.com/a", null, null, null));

            assertThat(created.code()).isEqualTo(code);
            assertThat(created.custom()).isFalse();
            assertThat(repository.findByCode(code)).contains(created);
        }

        @Test
        void usesTheAliasWhenOneIsGiven() {
            String alias = newCode();

            Link created = service().create(new CreateLinkCommand("https://example.com/a", alias, null, null));

            assertThat(created.code()).isEqualTo(alias);
            assertThat(created.custom()).isTrue();
        }

        @Test
        void rejectsAnAliasThatIsAlreadyTaken() {
            String alias = newCode();
            service().create(new CreateLinkCommand("https://first.example.com", alias, null, null));

            assertThatExceptionOfType(CodeTakenException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://second.example.com", alias, null, null)));

            assertThat(repository.findByCode(alias))
                    .map(Link::targetUrl)
                    .contains("https://first.example.com");
        }

        @Test
        void retriesWithAFreshCodeWhenTheGeneratedOneCollides() {
            // A collision is expected, not exceptional (api-design.md 5) - the conditional put
            // reports it and the next draw wins.
            String taken = newCode();
            String free = newCode();
            service(taken).create(new CreateLinkCommand("https://example.com/first", null, null, null));

            Link created = service(taken, free)
                    .create(new CreateLinkCommand("https://example.com/second", null, null, null));

            assertThat(created.code()).isEqualTo(free);
        }

        @Test
        void givesUpAfterTheConfiguredNumberOfAttempts() {
            String taken = newCode();
            service(taken).create(new CreateLinkCommand("https://example.com/a", null, null, null));

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                    service(taken, taken, taken).create(
                            new CreateLinkCommand("https://example.com/b", null, null, null)));
        }

        @Test
        void treatsABlankAliasAsNoAlias() {
            String code = newCode();

            Link created = service(code).create(new CreateLinkCommand("https://example.com/a", "  ", null, null));

            assertThat(created.code()).isEqualTo(code);
            assertThat(created.custom()).isFalse();
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        void defaultsToThirtyDaysFromNow() {
            Link created = service().create(new CreateLinkCommand("https://example.com/a", null, null, null));

            assertThat(created.expiresAt()).isEqualTo(NOW.plus(DEFAULT_TTL));
            assertThat(created.createdAt()).isEqualTo(NOW);
        }

        @Test
        void honoursAnExplicitExpiry() {
            Instant expiresAt = NOW.plus(Duration.ofDays(7));

            Link created = service().create(new CreateLinkCommand("https://example.com/a", null, expiresAt, null));

            assertThat(created.expiresAt()).isEqualTo(expiresAt);
        }

        @Test
        void honoursATtlInSeconds() {
            Link created = service().create(new CreateLinkCommand("https://example.com/a", null, null, 604800L));

            assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        }

        @Test
        void rejectsBothExpiryFormsAtOnce() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://example.com/a", null,
                            NOW.plus(Duration.ofDays(1)), 3600L)));
        }

        @Test
        void rejectsAnExpiryInThePast() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://example.com/a", null, NOW.minusSeconds(1), null)));
        }

        @Test
        void rejectsAnExpiryOfExactlyNow() {
            // A link that is born expired is a 410 nobody asked for.
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://example.com/a", null, NOW, null)));
        }

        @Test
        void rejectsANonPositiveTtl() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://example.com/a", null, null, 0L)));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("https://example.com/a", null, null, -1L)));
        }
    }

    @Nested
    @DisplayName("target url")
    class TargetUrl {

        @Test
        void rejectsAMissingUrl() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand(null, null, null, null)));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    service().create(new CreateLinkCommand("   ", null, null, null)));
        }

        @Test
        void storesAnyOtherUrlUntouched() {
            String url = "https://example.com/a?b=1#c";

            Link created = service().create(new CreateLinkCommand("  " + url + "  ", null, null, null));

            assertThat(created.targetUrl()).isEqualTo(url);
        }

        @Test
        void flattensOneOfOurOwnShortUrls() {
            // sho.rt/B -> sho.rt/A -> example.com is stored as sho.rt/B -> example.com, so the
            // redirect stays one hop and a chain can never cycle (api-design.md 2.1).
            String first = newCode();
            service(first).create(new CreateLinkCommand("https://example.com/final", null, null, null));

            Link second = service().create(
                    new CreateLinkCommand("https://sho.rt/" + first, null, null, null));

            assertThat(second.targetUrl()).isEqualTo("https://example.com/final");
        }

        @Test
        void keepsTheUrlWhenItLooksLikeOursButIsNotAKnownCode() {
            String url = "https://sho.rt/" + newCode();

            Link created = service().create(new CreateLinkCommand(url, null, null, null));

            assertThat(created.targetUrl()).isEqualTo(url);
        }

        @Test
        void flattensAgainstAnExpiredLinkToo() {
            // We are copying a destination, not inheriting a lifecycle.
            String first = newCode();
            service(first).create(new CreateLinkCommand("https://example.com/final", null,
                    NOW.plusSeconds(1), null));

            Link second = service().create(
                    new CreateLinkCommand("https://sho.rt/" + first, null, null, null));

            assertThat(second.targetUrl()).isEqualTo("https://example.com/final");
        }
    }
}
