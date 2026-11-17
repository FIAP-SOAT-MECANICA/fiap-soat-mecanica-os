package br.com.fiap.soat.mecanica.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Clock;

@EnableScheduling
@Configuration
public class AwsConfig {

    private final String region;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;

    public AwsConfig(@Value("${mecanica.aws.region}") String region,
                     @Value("${mecanica.aws.endpoint:}") String endpoint,
                     @Value("${mecanica.aws.access-key:}") String accessKey,
                     @Value("${mecanica.aws.secret-key:}") String secretKey,
                     @Value("${mecanica.aws.session-token:}") String sessionToken) {
        this.region = region;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SqsClient sqsClient() {
        return configurar(SqsClient.builder()).build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return configurar(DynamoDbClient.builder()).build();
    }

    /**
     * Mesma região, endpoint (LocalStack) e credenciais para todos os clientes. Sem chave
     * configurada, vale a cadeia padrão da AWS (no EKS, o papel do nó).
     */
    private <B extends AwsClientBuilder<B, ?>> B configurar(B builder) {
        builder.region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(credenciais()));
        }
        return builder;
    }

    private AwsCredentials credenciais() {
        return sessionToken.isBlank()
                ? AwsBasicCredentials.create(accessKey, secretKey)
                : AwsSessionCredentials.create(accessKey, secretKey, sessionToken);
    }
}
