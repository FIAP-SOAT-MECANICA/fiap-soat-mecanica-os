package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.exception.MensagemInvalidaException;
import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
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

import java.time.Instant;
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
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static SqsClient sqs;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String filaExecucao;
    private String filaBilling;
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
        filaExecucao = sqs.createQueue(r -> r.queueName("execucao-" + UUID.randomUUID())).queueUrl();
        filaBilling = sqs.createQueue(r -> r.queueName("billing-" + UUID.randomUUID())).queueUrl();
        publicador = new SqsPublicadorDeComandos(sqs, mapper, filaExecucao, filaBilling);
    }

    @Test
    @DisplayName("Deve publicar AbrirDiagnostico no formato do Execução, com o id da outbox e o traceparent")
    void devePublicarNoFormatoDoContrato() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        UUID osId = UUID.randomUUID();
        UUID veiculoId = UUID.randomUUID();
        MensagemOutbox mensagem = mensagem(id, DestinoMensagem.EXECUCAO, "AbrirDiagnostico", sagaId, osId,
                Map.of("osId", osId, "veiculoId", veiculoId, "placa", "ABC1D23"));

        publicador.publicar(mensagem);

        Message recebida = lerUma(filaExecucao);
        MessageEnvelope recebido = mapper.readValue(recebida.body(), MessageEnvelope.class);
        assertThat(recebido.messageId()).isEqualTo(id);
        assertThat(recebido.type()).isEqualTo("AbrirDiagnostico");
        assertThat(recebido.version()).isEqualTo(1);
        assertThat(recebido.sagaId()).isEqualTo(sagaId);
        assertThat(recebido.osId()).isEqualTo(osId);
        assertThat(recebido.correlationId()).isEqualTo("req-123");
        assertThat(recebido.causationId()).isNull();
        assertThat(recebido.occurredAt()).isEqualTo(AGORA);
        assertThat(recebido.payload().get("placa").asText()).isEqualTo("ABC1D23");
        assertThat(recebida.messageAttributes().get(RastreamentoMensagens.ATRIBUTO).stringValue())
                .isEqualTo(TRACEPARENT);
        recebido.validar();
    }

    @Test
    @DisplayName("Republicar a mesma linha da outbox reusa o messageId (o consumidor deduplica)")
    void deveReusarMessageIdAoRepublicar() throws Exception {
        UUID osId = UUID.randomUUID();
        MensagemOutbox mensagem = mensagem(UUID.randomUUID(), DestinoMensagem.BILLING, "CancelarOrcamento",
                UUID.randomUUID(), osId, Map.of("osId", osId));

        publicador.publicar(mensagem);
        publicador.publicar(mensagem);

        List<Message> recebidas = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(filaBilling).maxNumberOfMessages(10).waitTimeSeconds(1).build()).messages();
        assertThat(recebidas).hasSize(2);
        assertThat(recebidas).extracting(m -> lerEnvelope(m).messageId()).containsOnly(mensagem.id());
    }

    @Test
    @DisplayName("Não deve enviar nada quando a mensagem é inválida")
    void naoDeveEnviarMensagemInvalida() {
        UUID osId = UUID.randomUUID();
        MensagemOutbox semSaga = mensagem(UUID.randomUUID(), DestinoMensagem.EXECUCAO, "IniciarExecucao", null, osId,
                Map.of("osId", osId));

        assertThatThrownBy(() -> publicador.publicar(semSaga)).isInstanceOf(MensagemInvalidaException.class);

        List<Message> mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(filaExecucao).waitTimeSeconds(1).build()).messages();
        assertThat(mensagens).isEmpty();
    }

    @Test
    @DisplayName("Deve falhar quando a URL da fila não está configurada")
    void deveFalharSemUrlDaFila() {
        SqsPublicadorDeComandos semFila = new SqsPublicadorDeComandos(sqs, mapper, filaExecucao, "");
        UUID osId = UUID.randomUUID();
        MensagemOutbox mensagem = mensagem(UUID.randomUUID(), DestinoMensagem.BILLING, "GerarOrcamento",
                UUID.randomUUID(), osId, Map.of("osId", osId));

        assertThatThrownBy(() -> semFila.publicar(mensagem)).isInstanceOf(IllegalStateException.class);
    }

    private MensagemOutbox mensagem(UUID id, DestinoMensagem destino, String tipo, UUID sagaId, UUID osId,
                                    Map<String, Object> payload) {
        return new MensagemOutbox(id, destino, tipo, sagaId, osId, "req-123", null,
                mapper.valueToTree(payload), TRACEPARENT, AGORA);
    }

    private Message lerUma(String fila) {
        List<Message> mensagens = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(fila).waitTimeSeconds(1).messageAttributeNames("All").build()).messages();
        assertThat(mensagens).hasSize(1);
        return mensagens.get(0);
    }

    private MessageEnvelope lerEnvelope(Message mensagem) {
        try {
            return mapper.readValue(mensagem.body(), MessageEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
