package org.adoyo.shortenurl.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The lifecycle rules from docs/api-design.md 4, as tests. No Spring, no Docker - these run in
 * milliseconds.
 */
class LinkTests {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant CREATED = NOW.minus(Duration.ofDays(1));
    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));

    private static Link link(Instant expiresAt, Instant deletedAt) {
        return new Link("aX9k2Qp", "https://example.com/target", CREATED, expiresAt, deletedAt, false);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void acceptsALiveLink() {
            assertThatNoException().isThrownBy(() -> link(NEXT_MONTH, null));
        }

        @Test
        void rejectsAMissingCode() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link(null, "https://example.com", CREATED, NEXT_MONTH, null, false));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link("   ", "https://example.com", CREATED, NEXT_MONTH, null, false));
        }

        @Test
        void rejectsAMissingTargetUrl() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link("aX9k2Qp", null, CREATED, NEXT_MONTH, null, false));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link("aX9k2Qp", "", CREATED, NEXT_MONTH, null, false));
        }

        @Test
        void rejectsAMissingCreatedAt() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link("aX9k2Qp", "https://example.com", null, NEXT_MONTH, null, false));
        }

        @Test
        void rejectsAMissingExpiry() {
            // Every link expires (api-design.md 4). "Never expires" is not representable, which is
            // the whole point of making the field non-null here rather than defaulting it later.
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new Link("aX9k2Qp", "https://example.com", CREATED, null, null, false));
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        void aLinkIsActiveBeforeItsExpiry() {
            assertThat(link(NEXT_MONTH, null).status(NOW)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        void aLinkIsActiveRightUpToItsExpiry() {
            assertThat(link(NOW.plusSeconds(1), null).status(NOW)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        void aLinkIsExpiredAtExactlyItsExpiry() {
            // The boundary matters: api-design.md 4 says expiresAt <= now, so the instant it
            // arrives the link is already gone. Off by one here is a link that outlives its expiry.
            assertThat(link(NOW, null).status(NOW)).isEqualTo(LinkStatus.EXPIRED);
        }

        @Test
        void aLinkIsExpiredAfterItsExpiry() {
            assertThat(link(NOW.minusSeconds(1), null).status(NOW)).isEqualTo(LinkStatus.EXPIRED);
        }

        @Test
        void deletionBeatsExpiry() {
            // Both are 410 over the wire, but the model should record which one actually happened.
            Link both = link(NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(1)));
            assertThat(both.status(NOW)).isEqualTo(LinkStatus.DELETED);
        }

        @Test
        void aDeletedLinkIsDeletedEvenBeforeItsExpiry() {
            assertThat(link(NEXT_MONTH, NOW.minusSeconds(1)).status(NOW))
                    .isEqualTo(LinkStatus.DELETED);
        }
    }

    @Nested
    @DisplayName("resolvable")
    class Resolvable {

        @Test
        void onlyAnActiveLinkResolves() {
            assertThat(link(NEXT_MONTH, null).isResolvable(NOW)).isTrue();
            assertThat(link(NOW, null).isResolvable(NOW)).isFalse();
            assertThat(link(NEXT_MONTH, NOW.minusSeconds(1)).isResolvable(NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("renewal")
    class Renewal {

        @Test
        void renewingLeavesTheOriginalUntouched() {
            Link original = link(NOW.minusSeconds(1), null);

            Link renewed = original.renewedUntil(NEXT_MONTH);

            assertThat(renewed.expiresAt()).isEqualTo(NEXT_MONTH);
            assertThat(original.expiresAt()).isEqualTo(NOW.minusSeconds(1));
        }

        @Test
        void renewingCarriesEverythingElseOver() {
            Link original = link(NEXT_MONTH, null);

            Link renewed = original.renewedUntil(NOW.plus(Duration.ofDays(90)));

            assertThat(renewed.code()).isEqualTo(original.code());
            assertThat(renewed.targetUrl()).isEqualTo(original.targetUrl());
            assertThat(renewed.createdAt()).isEqualTo(original.createdAt());
            assertThat(renewed.custom()).isEqualTo(original.custom());
        }

        @Test
        void renewingADeletedLinkBringsItBack() {
            // api-design.md 4: a soft-deleted link is renewable. Clearing deletedAt is the
            // difference between "renew" and "set a new expiry on a corpse".
            Link deleted = link(NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(2)));

            Link renewed = deleted.renewedUntil(NEXT_MONTH);

            assertThat(renewed.deletedAt()).isNull();
            assertThat(renewed.status(NOW)).isEqualTo(LinkStatus.ACTIVE);
            assertThat(renewed.isResolvable(NOW)).isTrue();
        }

        @Test
        void renewingAnExpiredLinkMakesItActive() {
            assertThat(link(NOW.minusSeconds(1), null).renewedUntil(NEXT_MONTH).status(NOW))
                    .isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        void cannotRenewIntoAPermanentLink() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> link(NEXT_MONTH, null).renewedUntil(null));
        }
    }

    @Nested
    @DisplayName("soft delete")
    class SoftDelete {

        @Test
        void asDeletedMarksTheLinkDeleted() {
            Link deleted = link(NEXT_MONTH, null).asDeleted(NOW);

            assertThat(deleted.deletedAt()).isEqualTo(NOW);
            assertThat(deleted.status(NOW)).isEqualTo(LinkStatus.DELETED);
        }

        @Test
        void asDeletedKeepsTheExpiryAndTarget() {
            // Nothing is removed - that is what makes renewal possible later.
            Link deleted = link(NEXT_MONTH, null).asDeleted(NOW);

            assertThat(deleted.expiresAt()).isEqualTo(NEXT_MONTH);
            assertThat(deleted.targetUrl()).isEqualTo("https://example.com/target");
        }

        @Test
        void deletingTwiceKeepsTheFirstDeletionTime() {
            // DELETE is idempotent (api-design.md 2.5). A second call must not rewrite history.
            Instant firstDeletion = NOW.minus(Duration.ofDays(2));

            assertThat(link(NEXT_MONTH, firstDeletion).asDeleted(NOW).deletedAt())
                    .isEqualTo(firstDeletion);
        }

        @Test
        void aDeletedLinkCanBeRenewedAndDeletedAgain() {
            Link revived = link(NOW.minusSeconds(1), NOW.minusSeconds(1)).renewedUntil(NEXT_MONTH);

            assertThat(revived.asDeleted(NOW).deletedAt()).isEqualTo(NOW);
        }
    }
}
