package org.adoyo.shortenurl.persistence;

import org.adoyo.shortenurl.domain.Link;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;
import java.util.Optional;

public class LinkRepository {
    private static final String LIST_PK_PREFIX = "LINK#";
    private final DynamoDbClient dynamo;
    private final String tableName;
    private final int listShards;

    public LinkRepository(DynamoDbClient dynamo, String tableName, int listShards) {
        this.dynamo = dynamo;
        this.tableName = tableName;
        this.listShards = listShards;
    }

    public boolean saveIfAbsent(Link link) {
        try {
            dynamo.putItem(b -> b.tableName(tableName)
                    .item(toItem(link))
                    .conditionExpression("attribute_not_exists(code)")
            );
            return true;
        } catch (ConditionalCheckFailedException ex) {
            return false;
        }
    }

    public boolean replace(Link link) {
        try {
            dynamo.putItem(b -> b.tableName(tableName)
                    .item(toItem(link))
                    .conditionExpression("attribute_exists(code)"));
            return true;
        } catch (ConditionalCheckFailedException ex) {
            return false;
        }

    }

    public Optional<Link> findByCode(String code) {
        return read()
    }

    private Optional<Link> read(String code, boolean consistent) {
        GetItemResponse response = dynamo.getItem(b -> b.tableName(tableName)
                .key(Map.of("code", AttributeValue.fromS(link.code))))
    }



}
