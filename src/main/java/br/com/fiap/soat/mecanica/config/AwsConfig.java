package br.com.fiap.soat.mecanica.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;
import java.time.Clock;

@Configuration
public class AwsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SqsClient sqsClient(@Value("${mecanica.aws.region}") String region,
                               @Value("${mecanica.aws.endpoint:}") String endpoint,
                               @Value("${mecanica.aws.access-key:}") String accessKey,
                               @Value("${mecanica.aws.secret-key:}") String secretKey,
                               @Value("${mecanica.aws.session-token:}") String sessionToken) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    credenciais(accessKey, secretKey, sessionToken)
            ));
        }
        return builder.build();
    }

    private static AwsCredentials credenciais(String accessKey, String secretKey, String sessionToken) {
        return sessionToken.isBlank()
                ? AwsBasicCredentials.create(accessKey, secretKey)
                : AwsSessionCredentials.create(accessKey, secretKey, sessionToken);
    }
}
