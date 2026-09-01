package org.adoyo.shortenurl.config;

import java.util.List;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the schema the rest of the design leans on, rather than that CreateTable was called:
 * the redirect path needs code as a bare partition key, the list endpoint needs the GSI, and the
 * whole uniqueness story is one conditional put.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TableBootstrapTests {

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    AppProperties app;

    @Test
    void createsAllThreeTables() {
        assertThat(dynamo.listTables().tableNames())
                .containsAll(app.tables().all());
    }

    @Test
    void linksIsKeyedOnCodeAloneAndCarriesTheListIndex() {
        TableDescription table = describe(app.tables().links());

        assertThat(names(table.keySchema())).containsExactly("code");

        GlobalSecondaryIndexDescription gsi = table.globalSecondaryIndexes().stream()
                .filter(i -> i.indexName().equals(TableBootstrap.LINKS_GSI))
                .findFirst()
                .orElseThrow();
        assertThat(names(gsi.keySchema())).containsExactly("listPk", "createdAt");
        assertThat(gsi.projection().nonKeyAttributes())
                .contains("targetUrl", "expiresAt", "deletedAt", "custom");
    }

    @Test
    void clicksIsKeyedOnCodeAndStat() {
        assertThat(names(describe(app.tables().clicks()).keySchema()))
                .containsExactly("code", "stat");
    }

    @Test
    void bootstrapIsIdempotent() {
        // Second run against a populated database must be a no-op, since every local start and
        // every test context reuse goes through it.
        long before = dynamo.listTables().tableNames().size();
        new TableBootstrap(dynamo, app).run(null);
        assertThat(dynamo.listTables().tableNames()).hasSize((int) before);
    }

    @Test
    void conditionalPutRejectsADuplicateCode() {
        var item = java.util.Map.of("code", AttributeValue.fromS("dupe-test"));
        dynamo.putItem(b -> b.tableName(app.tables().links()).item(item));

        assertThatThrownBy(() -> dynamo.putItem(b -> b.tableName(app.tables().links())
                .item(item)
                .conditionExpression("attribute_not_exists(code)")))
                .isInstanceOf(ConditionalCheckFailedException.class);
    }

    private TableDescription describe(String table) {
        return dynamo.describeTable(b -> b.tableName(table)).table();
    }

    private static List<String> names(List<KeySchemaElement> schema) {
        return schema.stream().map(KeySchemaElement::attributeName).toList();
    }
}
