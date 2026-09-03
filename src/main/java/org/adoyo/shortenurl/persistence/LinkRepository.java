package org.adoyo.shortenurl.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.adoyo.shortenurl.domain.Link;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Links in DynamoDB: docs/api-design.md 8.
 *
 * <p>Uses the low-level client rather than the enhanced one on purpose. Mapping an immutable
 * record with the enhanced client means annotating it and writing a builder class, which drags AWS
 * types into the domain; here the mapping lives at the edge and Link stays testable with nothing
 * running.
 */
public class LinkRepository {

    private static final String LIST_PK_PREFIX = "LINKS#";

    private final DynamoDbClient dynamo;
    private final String tableName;
    private final int listShards;

    public LinkRepository(DynamoDbClient dynamo, String tableName, int listShards) {
        this.dynamo = dynamo;
        this.tableName = tableName;
        this.listShards = listShards;
    }

    /** Stores the link, or returns false if the code is already taken. */
    public boolean saveIfAbsent(Link link) {
        try {
            dynamo.putItem(b -> b.tableName(tableName)
                    .item(toItem(link))
                    .conditionExpression("attribute_not_exists(code)"));
            return true;
        }
        catch (ConditionalCheckFailedException ex) {
            return false;
        }
    }

    /** Overwrites an existing link, or returns false if the code does not exist. */
    public boolean replace(Link link) {
        try {
            dynamo.putItem(b -> b.tableName(tableName)
                    .item(toItem(link))
                    .conditionExpression("attribute_exists(code)"));
            return true;
        }
        catch (ConditionalCheckFailedException ex) {
            return false;
        }
    }

    public Optional<Link> findByCode(String code) {
        return read(code, false).or(() -> read(code, true));
    }

    private Optional<Link> read(String code, boolean consistent) {
        GetItemResponse response = dynamo.getItem(b -> b.tableName(tableName)
                .key(Map.of("code", AttributeValue.fromS(code)))
                .consistentRead(consistent));
        return response.item().isEmpty()
                ? Optional.empty()
                : Optional.of(fromItem(response.item()));
    }

    private Map<String, AttributeValue> toItem(Link link) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("code", AttributeValue.fromS(link.code()));
        item.put("targetUrl", AttributeValue.fromS(link.targetUrl()));
        item.put("createdAt", AttributeValue.fromS(link.createdAt().toString()));
        item.put("expiresAt", AttributeValue.fromS(link.expiresAt().toString()));
        item.put("custom", AttributeValue.fromBool(link.custom()));
        item.put("listPk", AttributeValue.fromS(listPk(link.code())));
        if (link.deletedAt() != null) {
            item.put("deletedAt", AttributeValue.fromS(link.deletedAt().toString()));
        }
        return item;
    }

    private static Link fromItem(Map<String, AttributeValue> item) {
        return new Link(
                item.get("code").s(),
                item.get("targetUrl").s(),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("expiresAt").s()),
                instantOrNull(item.get("deletedAt")),
                item.get("custom").bool());
    }

    private static Instant instantOrNull(AttributeValue value) {
        return value == null ? null : Instant.parse(value.s());
    }

    private String listPk(String code) {
        return LIST_PK_PREFIX + Math.floorMod(code.hashCode(), listShards);
    }
}
