package org.adoyo.shortenurl.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Builds the DynamoDB clients. One switch: if {@code aws.dynamodb.endpoint} is set we are talking
 * to DynamoDB Local, so the endpoint is overridden and dummy credentials are supplied - DynamoDB
 * Local ignores credentials entirely, but the SDK will not build a client without a provider.
 *
 * <p>With the endpoint unset the SDK's default credentials chain and regional endpoint apply, so
 * the same build runs against real AWS with no code change.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AwsProperties.class, AppProperties.class })
public class DynamoDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConfig.class);

    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties props) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(props.region()));

        if (props.dynamodb().isLocal()) {
            log.info("Using DynamoDB Local at {}", props.dynamodb().endpoint());
            builder.endpointOverride(URI.create(props.dynamodb().endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }

        return builder.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }
}
