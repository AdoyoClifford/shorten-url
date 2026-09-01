package org.adoyo.shortenurl.config;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the two tables from docs/api-design.md 8 if they are missing.
 *
 * <p>Gated on {@code app.bootstrap-tables}, which is only true in the local profile. Deployed
 * environments get their tables from IaC: an application that creates its own schema will
 * eventually create the wrong one against the wrong account.
 *
 * <p>Written against the low-level client rather than the enhanced client's
 * {@code createTable} so the index configuration here is exactly what the design doc specifies,
 * independent of any entity mapping added later. No table carries a TTL attribute: per
 * api-design.md 4 nothing is ever hard-deleted, and a renewable link needs something left to renew.
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap-tables", havingValue = "true")
public class TableBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TableBootstrap.class);

    static final String LINKS_GSI = "all-links-index";

    private final DynamoDbClient dynamo;
    private final AppProperties app;

    TableBootstrap(DynamoDbClient dynamo, AppProperties app) {
        this.dynamo = dynamo;
        this.app = app;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = Set.copyOf(dynamo.listTables().tableNames());
        log.info("Bootstrapping tables (existing: {})", existing);

        createIfAbsent(existing, links());
        createIfAbsent(existing, clicks());
    }

    private void createIfAbsent(Set<String> existing, CreateTableRequest request) {
        if (existing.contains(request.tableName())) {
            log.debug("Table {} already exists", request.tableName());
            return;
        }
        dynamo.createTable(request);
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(request.tableName()));
        log.info("Created table {}", request.tableName());
    }

    /**
     * links: code is the partition key and the only thing the redirect path touches.
     * The GSI backs the list endpoint, sharded on listPk so writes do not funnel into one
     * index partition (api-design.md 8.1).
     */
    private CreateTableRequest links() {
        return CreateTableRequest.builder()
                .tableName(app.tables().links())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attr("code", ScalarAttributeType.S),
                        attr("listPk", ScalarAttributeType.S),
                        attr("createdAt", ScalarAttributeType.S))
                .keySchema(key("code", KeyType.HASH))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(LINKS_GSI)
                        .keySchema(key("listPk", KeyType.HASH), key("createdAt", KeyType.RANGE))
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.INCLUDE)
                                .nonKeyAttributes("targetUrl", "expiresAt", "deletedAt", "custom")
                                .build())
                        .build())
                .build();
    }

    /** clicks: one item per (code, bucket), incremented with ADD (api-design.md 7.3). */
    private CreateTableRequest clicks() {
        return CreateTableRequest.builder()
                .tableName(app.tables().clicks())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attr("code", ScalarAttributeType.S),
                        attr("stat", ScalarAttributeType.S))
                .keySchema(key("code", KeyType.HASH), key("stat", KeyType.RANGE))
                .build();
    }

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
