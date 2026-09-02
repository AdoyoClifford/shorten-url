package org.adoyo.shortenurl.persistence;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.adoyo.shortenurl.domain.Link;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/api-design.md 8.3: the redirect reads eventually consistent, and retries once strongly
 * consistent on a miss, so nobody ever sees a 404 on a link they just created.
 *
 * <p>DynamoDB Local has no replication lag, so this cannot be shown against a container - it needs
 * a client that can be told to miss.
 */
class LinkReadConsistencyTests {

    private static final String CODE = "aX9k2Qp";

    @Test
    void readsEventuallyConsistentFirst() {
        RecordingDynamoDb dynamo = new RecordingDynamoDb(item());
        LinkRepository repository = new LinkRepository(dynamo, "links", 10);

        assertThat(repository.findByCode(CODE)).isPresent();

        // One read, and the cheap one. Making every redirect strongly consistent doubles the RCU
        // cost of the hottest operation in the system.
        assertThat(dynamo.consistentReads).containsExactly(false);
    }

    @Test
    void retriesStronglyConsistentWhenTheFirstReadMisses() {
        RecordingDynamoDb dynamo = new RecordingDynamoDb(Map.of(), item());
        LinkRepository repository = new LinkRepository(dynamo, "links", 10);

        assertThat(repository.findByCode(CODE)).isPresent();

        assertThat(dynamo.consistentReads).containsExactly(false, true);
    }

    @Test
    void givesUpAfterTheStronglyConsistentRetry() {
        RecordingDynamoDb dynamo = new RecordingDynamoDb(Map.of(), Map.of());
        LinkRepository repository = new LinkRepository(dynamo, "links", 10);

        assertThat(repository.findByCode(CODE)).isEmpty();

        // Exactly two. A third read cannot tell you anything the strongly consistent one did not.
        assertThat(dynamo.consistentReads).containsExactly(false, true);
    }

    private static Map<String, AttributeValue> item() {
        return Map.of(
                "code", AttributeValue.fromS(CODE),
                "targetUrl", AttributeValue.fromS("https://example.com/a"),
                "createdAt", AttributeValue.fromS(Instant.parse("2026-09-01T12:00:00Z").toString()),
                "expiresAt", AttributeValue.fromS(Instant.parse("2026-10-01T12:00:00Z").toString()),
                "custom", AttributeValue.fromBool(false),
                "listPk", AttributeValue.fromS("LINKS#3"));
    }

    /**
     * Only getItem is implemented - every other method on DynamoDbClient defaults to throwing,
     * so anything else the repository reaches for fails loudly rather than silently passing.
     */
    private static final class RecordingDynamoDb implements DynamoDbClient {

        private final Deque<Map<String, AttributeValue>> responses = new ArrayDeque<>();
        private final List<Boolean> consistentReads = new ArrayList<>();

        @SafeVarargs
        private RecordingDynamoDb(Map<String, AttributeValue>... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public GetItemResponse getItem(GetItemRequest request) {
            consistentReads.add(request.consistentRead());
            Map<String, AttributeValue> item = responses.isEmpty() ? Map.of() : responses.poll();
            return GetItemResponse.builder().item(item).build();
        }

        @Override
        public String serviceName() {
            return "dynamodb";
        }

        @Override
        public void close() {
        }
    }
}
