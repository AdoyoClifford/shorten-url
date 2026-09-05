package org.adoyo.shortenurl.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.domain.LinkStatus;
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
 * Renewal and soft delete: docs/api-design.md 2.4, 2.5 and 4.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LinkLifecycleTests {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));

    @Autowired
    LinkRepository repository;

    private LinkService service() {
        return new LinkService(repository, LinkLifecycleTests::newCode,
                new ShortUrlParser("https://sho.rt"), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(30), 3);
    }

    private static String newCode() {
        return "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String existingLink() {
        return service().create(new CreateLinkCommand("https://example.com/a", null, null, null)).code();
    }

    @Nested
    @DisplayName("renew")
    class Renew {

        @Test
        void movesTheExpiryOut() {
            String code = existingLink();
            Instant later = NEXT_MONTH.plus(Duration.ofDays(60));

            Link renewed = service().renew(code, later);

            assertThat(renewed.expiresAt()).isEqualTo(later);
            assertThat(repository.findByCode(code)).map(Link::expiresAt).contains(later);
        }

        @Test
        void bringsBackASoftDeletedLink() {
            // The point of soft delete (api-design.md 4): deletion is a state, not an ending.
            String code = existingLink();
            service().delete(code);

            Link renewed = service().renew(code, NEXT_MONTH);

            assertThat(renewed.deletedAt()).isNull();
            assertThat(renewed.status(NOW)).isEqualTo(LinkStatus.ACTIVE);
            assertThat(repository.findByCode(code)).map(Link::deletedAt).isEmpty();
        }

        @Test
        void keepsTheTargetAndTheCreationTime() {
            // A link that can come back has to come back pointing where it always pointed.
            String code = existingLink();

            Link renewed = service().renew(code, NEXT_MONTH);

            assertThat(renewed.targetUrl()).isEqualTo("https://example.com/a");
            assertThat(renewed.createdAt()).isEqualTo(NOW);
        }

        @Test
        void rejectsAnExpiryThatIsNotInTheFuture() {
            String code = existingLink();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service().renew(code, NOW));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service().renew(code, NOW.minusSeconds(1)));
        }

        @Test
        void rejectsAMissingExpiry() {
            String code = existingLink();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service().renew(code, null));
        }

        @Test
        void refusesACodeThatDoesNotExist() {
            assertThatExceptionOfType(LinkNotFoundException.class)
                    .isThrownBy(() -> service().renew(newCode(), NEXT_MONTH));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        void marksTheLinkDeleted() {
            String code = existingLink();

            Link deleted = service().delete(code);

            assertThat(deleted.deletedAt()).isEqualTo(NOW);
            assertThat(repository.findByCode(code)).map(Link::deletedAt).contains(NOW);
        }

        @Test
        void keepsTheTargetAndExpiry() {
            String code = existingLink();

            Link deleted = service().delete(code);

            assertThat(deleted.targetUrl()).isEqualTo("https://example.com/a");
            assertThat(deleted.expiresAt()).isEqualTo(NEXT_MONTH);
        }

        @Test
        void isIdempotentAndDoesNotRewriteHistory() {
            // A second DELETE must not move the deletion time - that is the difference between
            // idempotent and merely repeatable.
            String code = existingLink();
            Instant firstDeletion = service().delete(code).deletedAt();

            LinkService later = new LinkService(repository, LinkLifecycleTests::newCode,
                    new ShortUrlParser("https://sho.rt"),
                    Clock.fixed(NOW.plus(Duration.ofDays(3)), ZoneOffset.UTC),
                    Duration.ofDays(30), 3);

            assertThat(later.delete(code).deletedAt()).isEqualTo(firstDeletion);
            assertThat(repository.findByCode(code)).map(Link::deletedAt).contains(firstDeletion);
        }

        @Test
        void refusesACodeThatDoesNotExist() {
            assertThatExceptionOfType(LinkNotFoundException.class)
                    .isThrownBy(() -> service().delete(newCode()));
        }
    }
}
