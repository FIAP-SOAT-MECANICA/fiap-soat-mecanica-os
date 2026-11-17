package br.com.fiap.soat.mecanica.adapters.out.historico;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mesma chave da infra/historico: PK osId, SK ocorridoEm. */
@Testcontainers
class DynamoHistoricoOrdemServicoRepositoryLocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private static DynamoDbClient dynamo;
    private static DynamoHistoricoOrdemServicoRepository repository;

    @BeforeAll
    static void criarTabela() {
        dynamo = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        dynamo.createTable(r -> r.tableName("os-historico")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("osId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("ocorridoEm").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("osId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("ocorridoEm").keyType(KeyType.RANGE).build()));
        repository = new DynamoHistoricoOrdemServicoRepository(dynamo, "os-historico");
    }

    @AfterAll
    static void fechar() {
        dynamo.close();
    }

    @Test
    @DisplayName("Devolve as transições de uma OS em ordem cronológica, mesmo gravadas fora de ordem")
    void deveListarEmOrdemCronologica() {
        UUID osId = UUID.randomUUID();
        repository.registrar(entrada(osId, "2026-11-17T10:02:00.000Z", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO"));
        repository.registrar(entrada(osId, "2026-11-17T10:00:00.000Z", null, "RECEBIDA"));
        repository.registrar(entrada(osId, "2026-11-17T10:01:00.500Z", "RECEBIDA", "EM_DIAGNOSTICO"));
        repository.registrar(entrada(UUID.randomUUID(), "2026-11-17T10:00:00.000Z", null, "RECEBIDA"));

        List<EntradaHistorico> historico = repository.listarPorOsId(osId);

        assertThat(historico).extracting(EntradaHistorico::situacaoNova)
                .containsExactly("RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO");
        assertThat(historico.get(0).situacaoAnterior()).isNull();
        assertThat(historico.get(1).ocorridoEm()).isEqualTo(Instant.parse("2026-11-17T10:01:00.500Z"));
    }

    @Test
    @DisplayName("Gravar a mesma entrada de novo (republicação da outbox) não duplica")
    void deveSerIdempotente() {
        UUID osId = UUID.randomUUID();
        EntradaHistorico entrada = entrada(osId, "2026-11-17T10:00:00.000Z", null, "RECEBIDA");

        repository.registrar(entrada);
        repository.registrar(entrada);

        assertThat(repository.listarPorOsId(osId)).hasSize(1);
    }

    @Test
    @DisplayName("Duas transições no mesmo milissegundo não se sobrescrevem")
    void mesmoMilissegundo() {
        UUID osId = UUID.randomUUID();
        repository.registrar(entrada(osId, "2026-11-17T10:00:00.000Z", null, "RECEBIDA"));
        repository.registrar(entrada(osId, "2026-11-17T10:00:00.000Z", "RECEBIDA", "CANCELADA"));

        assertThat(repository.listarPorOsId(osId)).hasSize(2);
    }

    private static EntradaHistorico entrada(UUID osId, String quando, String anterior, String nova) {
        return new EntradaHistorico(osId, Instant.parse(quando), UUID.randomUUID(), anterior, nova, "teste");
    }
}
