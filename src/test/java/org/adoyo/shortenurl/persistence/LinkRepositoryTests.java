package org.adoyo.shortenurl.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.adoyo.shortenurl.config.AppProperties;
import org.adoyo.shortenurl.domain.Link;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persistence rules from docs/api-design.md 8: one conditional put is the whole uniqueness
 * story, and nothing is ever hard-deleted.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LinkRepositoryTests {

    @Autowired
    LinkRepository repository;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    AppProperties app;

    private static final Instant CREATED = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant EXPIRES = CREATED.plus(Duration.ofDays(30));

    /** A fresh code per test - the table is shared across the whole class. */
    private static String newCode() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static Link link(String code, String target) {
        return new Link(code, target, CREATED, EXPIRES, null, false);
    }

    private Map<String, AttributeValue> rawItem(String code) {
        return dynamo.getItem(b -> b.tableName(app.tables().links())
                .key(Map.of("code", AttributeValue.fromS(code)))
                .consistentRead(true)).item();
    }

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        void savesAndReadsBackEveryField() {
            Link saved = new Link(newCode(), "https://example.com/a", CREATED, EXPIRES, null, true);

            assertThat(repository.saveIfAbsent(saved)).isTrue();

            assertThat(repository.findByCode(saved.code())).contains(saved);
        }

        @Test
        void refusesToOverwriteAnExistingCode() {
            // The conditional put IS the unique constraint (api-design.md 8.2). If this ever
            // returns true twice, a custom alias can silently steal somebody else's link.
            String code = newCode();
            repository.saveIfAbsent(link(code, "https://first.example.com"));

            boolean saved = repository.saveIfAbsent(link(code, "https://second.example.com"));

            assertThat(saved).isFalse();
            assertThat(repository.findByCode(code))
                    .map(Link::targetUrl)
                    .contains("https://first.example.com");
        }

        @Test
        void returnsEmptyForAnUnknownCode() {
            assertThat(repository.findByCode(newCode())).isEmpty();
        }
    }

    @Nested
    @DisplayName("marshalling")
    class Marshalling {

        @Test
        void omitsDeletedAtWhileTheLinkIsLive() {
            // An absent attribute, not a NULL one: DynamoDB filter expressions treat the two
            // differently, and a stored NULL is a value you then have to remember to ignore.
            String code = newCode();
            repository.saveIfAbsent(link(code, "https://example.com/a"));

            assertThat(rawItem(code)).doesNotContainKey("deletedAt");
        }

        @Test
        void roundTripsADeletedLink() {
            String code = newCode();
            Instant deletedAt = CREATED.plus(Duration.ofDays(2));
            repository.saveIfAbsent(new Link(code, "https://example.com/a", CREATED, EXPIRES, deletedAt, false));

            assertThat(repository.findByCode(code))
                    .map(Link::deletedAt)
                    .contains(deletedAt);
        }

        @Test
        void keepsInstantsExactToTheNanosecond() {
            // ISO-8601 strings, not epoch millis: truncating here would quietly move an expiry.
            String code = newCode();
            Instant precise = Instant.parse("2026-09-01T12:00:00.123456789Z");
            repository.saveIfAbsent(new Link(code, "https://example.com/a", precise, EXPIRES, null, false));

            assertThat(repository.findByCode(code))
                    .map(Link::createdAt)
                    .contains(precise);
        }

        @Test
        void storesTheCustomFlag() {
            String custom = newCode();
            String generated = newCode();
            repository.saveIfAbsent(new Link(custom, "https://example.com/a", CREATED, EXPIRES, null, true));
            repository.saveIfAbsent(new Link(generated, "https://example.com/a", CREATED, EXPIRES, null, false));

            assertThat(repository.findByCode(custom)).map(Link::custom).contains(true);
            assertThat(repository.findByCode(generated)).map(Link::custom).contains(false);
        }
    }

    @Nested
    @DisplayName("replacing")
    class Replacing {

        @Test
        void replacesAnExistingLink() {
            String code = newCode();
            Link original = link(code, "https://example.com/a");
            repository.saveIfAbsent(original);
            Instant renewedUntil = EXPIRES.plus(Duration.ofDays(60));

            assertThat(repository.replace(original.renewedUntil(renewedUntil))).isTrue();

            assertThat(repository.findByCode(code)).map(Link::expiresAt).contains(renewedUntil);
        }

        @Test
        void removesDeletedAtWhenALinkIsRenewed() {
            // Renewal has to clear the attribute, not just stop reading it.
            String code = newCode();
            repository.saveIfAbsent(new Link(code, "https://example.com/a", CREATED, EXPIRES,
                    CREATED.plus(Duration.ofDays(1)), false));

            repository.replace(link(code, "https://example.com/a").renewedUntil(EXPIRES));

            assertThat(rawItem(code)).doesNotContainKey("deletedAt");
            assertThat(repository.findByCode(code)).map(Link::deletedAt).isEmpty();
        }

        @Test
        void refusesToReplaceACodeThatDoesNotExist() {
            // Otherwise replace() would resurrect a code as a side effect of a PATCH on a typo.
            assertThat(repository.replace(link(newCode(), "https://example.com/a"))).isFalse();
        }
    }

    @Nested
    @DisplayName("list shard")
    class ListShard {

        @Test
        void assignsAShardWithinTheConfiguredRange() {
            String code = newCode();
            repository.saveIfAbsent(link(code, "https://example.com/a"));

            String listPk = rawItem(code).get("listPk").s();

            assertThat(listPk).startsWith("LINKS#");
            int shard = Integer.parseInt(listPk.substring("LINKS#".length()));
            assertThat(shard).isBetween(0, app.listShards() - 1);
        }

        @Test
        void keepsTheSameShardWhenALinkIsReplaced() {
            // Derived from the code, not drawn at random: a link that hops shards on every write
            // is a link the list query can return twice, or miss mid-page.
            String code = newCode();
            Link original = link(code, "https://example.com/a");
            repository.saveIfAbsent(original);
            String before = rawItem(code).get("listPk").s();

            repository.replace(original.renewedUntil(EXPIRES.plus(Duration.ofDays(1))));

            assertThat(rawItem(code).get("listPk").s()).isEqualTo(before);
        }
    }
}
