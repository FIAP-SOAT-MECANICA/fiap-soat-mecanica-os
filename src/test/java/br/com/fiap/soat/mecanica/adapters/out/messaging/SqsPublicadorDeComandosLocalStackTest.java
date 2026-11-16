package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.exception.MensagemInvalidaException;
import br.com.fiap.soat.mecanica.application.saga.Comando;
import br.com.fiap.soat.mecanica.application.saga.DestinoComando;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SqsPublicadorDeComandosLocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.SQS);

    private static final Instant AGORA = Instant.parse("2026-11-15T19:00:00Z");

    private static SqsClient sqs;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String filaUrl;
    private SqsPublicadorDeComandos publicador;

    @BeforeAll
    static void criarCliente() {
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @AfterAll
    static void fecharCliente() {
        sqs.close();
    }

    @BeforeEach
    void setUp() {
        filaUrl = sqs.createQueue(r -> r.queueName("comandos-" + UUID.randomUUID())).queueUrl();
        publicador = new SqsPublicadorDeComandos(sqs, mapper, Clock.fixed(AGORA, ZoneOffset.UTC), filaUrl);
    }

    @Test
    @DisplayName("Deve publicar AbrirDiagnostico na fila no formato que o Execução aceita")
    void devePublicarNoFormatoDoContrato() throws Exception {
        UUID sagaId = UUID.randomUUID();
        UUID osId = UUID.randomUUID();
        UUID veiculoId = UUID.randomUUID();
        Comando comando = new Comando(DestinoComando.EXECUCAO, "AbrirDiagnostico", sagaId, osId,
                "req-123", null, Map.of("osId", osId, "veiculoId", veiculoId, "placa", "ABC1D23"));

        UUID messageId = publicador.publicar(comando);

        List<Message> mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(filaUrl).waitTimeSeconds(1).build()).messages();
        assertThat(mensagens).hasSize(1);

        MessageEnvelope recebido = mapper.readValue(mensagens.get(0).body(), MessageEnvelope.class);
        assertThat(recebido.messageId()).isEqualTo(messageId);
        assertThat(recebido.type()).isEqualTo("AbrirDiagnostico");
        assertThat(recebido.version()).isEqualTo(1);
        assertThat(recebido.sagaId()).isEqualTo(sagaId);
        assertThat(recebido.osId()).isEqualTo(osId);
        assertThat(recebido.correlationId()).isEqualTo("req-123");
        assertThat(recebido.causationId()).isNull();
        assertThat(recebido.occurredAt()).isEqualTo(AGORA);
        assertThat(recebido.payload().get("osId").asText()).isEqualTo(osId.toString());
        assertThat(recebido.payload().get("veiculoId").asText()).isEqualTo(veiculoId.toString());
        assertThat(recebido.payload().get("placa").asText()).isEqualTo("ABC1D23");
        recebido.validar();
    }

    @Test
    @DisplayName("Deve gerar um messageId novo a cada publicação")
    void deveGerarMessageIdNovo() {
        UUID osId = UUID.randomUUID();
        Comando comando = new Comando(DestinoComando.EXECUCAO, "IniciarExecucao", UUID.randomUUID(), osId,
                "req-123", UUID.randomUUID(), Map.of("osId", osId));

        assertThat(publicador.publicar(comando)).isNotEqualTo(publicador.publicar(comando));
    }

    @Test
    @DisplayName("Não deve enviar nada quando o comando é inválido")
    void naoDeveEnviarComandoInvalido() {
        UUID osId = UUID.randomUUID();
        Comando semSaga = new Comando(DestinoComando.EXECUCAO, "IniciarExecucao", null, osId,
                "req-123", null, Map.of("osId", osId));

        assertThatThrownBy(() -> publicador.publicar(semSaga)).isInstanceOf(MensagemInvalidaException.class);

        List<Message> mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(filaUrl).waitTimeSeconds(1).build()).messages();
        assertThat(mensagens).isEmpty();
    }

    @Test
    @DisplayName("Deve falhar quando a URL da fila não está configurada")
    void deveFalharSemUrlDaFila() {
        SqsPublicadorDeComandos semFila = new SqsPublicadorDeComandos(sqs, mapper, Clock.systemUTC(), "");
        UUID osId = UUID.randomUUID();
        Comando comando = new Comando(DestinoComando.EXECUCAO, "IniciarExecucao", UUID.randomUUID(), osId,
                "req-123", null, Map.of("osId", osId));

        assertThatThrownBy(() -> semFila.publicar(comando)).isInstanceOf(IllegalStateException.class);
    }
}