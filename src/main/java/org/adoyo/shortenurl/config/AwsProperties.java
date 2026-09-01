package org.adoyo.shortenurl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AWS connection settings.
 *
 * <p>{@code dynamodb.endpoint} is the switch between local and real AWS: when it is set the client
 * is pointed at DynamoDB Local with dummy credentials, when it is absent the SDK's default
 * credentials chain and regional endpoint are used. See {@link DynamoDbConfig}.
 */
@ConfigurationProperties("aws")
public record AwsProperties(

        @DefaultValue("us-east-1") String region,

        @DefaultValue DynamoDb dynamodb) {

    public record DynamoDb(String endpoint) {

        public boolean isLocal() {
            return endpoint != null && !endpoint.isBlank();
        }
    }
}
