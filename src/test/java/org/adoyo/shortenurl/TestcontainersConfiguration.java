package org.adoyo.shortenurl;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DynamoDB Local for tests - the same image docker-compose.yml runs, so conditional puts and ADD
 * counters behave identically in dev, test and production. Not LocalStack: we need exactly one AWS
 * service, and this image boots in about a second, which is felt on every test run.
 *
 * <p>There is deliberately no src/test/resources/application.properties. A file of that name would
 * shadow the real one instead of merging with it, and the application's own configuration would
 * then never be loaded by any test - so a property that stops the app booting would still pass a
 * green build. Tests run the real config; only the endpoint is overridden, here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final int DYNAMODB_PORT = 8000;

    @Bean
    public GenericContainer<?> dynamoDbLocal() {
        return new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.2"))
                .withExposedPorts(DYNAMODB_PORT)
                // In-memory: each test run starts from an empty database, and there is no volume
                // to clean up afterwards.
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
    }

    /**
     * Taking the container as a parameter is what forces it started before the port is read.
     * Dynamic properties outrank every properties file, so this beats application-local.properties.
     */
    @Bean
    public DynamicPropertyRegistrar dynamoDbProperties(GenericContainer<?> dynamoDbLocal) {
        return registry -> registry.add("aws.dynamodb.endpoint",
                () -> "http://" + dynamoDbLocal.getHost() + ":" + dynamoDbLocal.getMappedPort(DYNAMODB_PORT));
    }
}
