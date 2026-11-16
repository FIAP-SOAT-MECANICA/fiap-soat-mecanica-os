package br.com.fiap.soat.mecanica.adapters.in.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.MessageEnvelope;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.TratadorDeEventos;
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
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsConsumidorDeRespostasLocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.SQS);

    private static SqsClient sqs;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final TratadorQueAnota anotador = new TratadorQueAnota();
    private String filaUrl;
    private SqsConsumidorDeRespostas consumidor;

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
        filaUrl = sqs.createQueue(r -> r
                .queueName("respostas-" + UUID.randomUUID())
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1"))).queueUrl();
        consumidor = new SqsConsumidorDeRespostas(sqs, mapper, filaUrl,
                List.of(anotador, new TratadorQueFalha()));
    }

    @Test
    @DisplayName("Deve tratar o evento e apagar a mensagem da fila")
    void deveTratarEApagar() throws Exception {
        MessageEnvelope envelope = envelope("PecasReservadas");
        enviar(mapper.writeValueAsString(envelope));

        consumidor.consumir();

        assertThat(anotador.recebidos).hasSize(1);
        EventoRecebido evento = anotador.recebidos.get(0);
        assertThat(evento.messageId()).isEqualTo(envelope.messageId());
        assertThat(evento.sagaId()).isEqualTo(envelope.sagaId());
        assertThat(evento.osId()).isEqualTo(envelope.osId());
        assertThat(evento.payload().get("osId").asText()).isEqualTo(envelope.osId().toString());
        assertThat(lerDeNovo()).isEmpty();
    }

    @Test
    @DisplayName("Deve manter na fila a mensagem cujo tratamento falhou")
    void deveManterQuandoTratamentoFalha() throws Exception {
        enviar(mapper.writeValueAsString(envelope("ReservaPecasRecusada")));

        consumidor.consumir();

        assertThat(lerDeNovo()).hasSize(1);
    }

    @Test
    @DisplayName("Deve manter na fila o evento sem tratador")
    void deveManterTipoDesconhecido() throws Exception {
        enviar(mapper.writeValueAsString(envelope("EventoQueNinguemTrata")));

        consumidor.consumir();

        assertThat(anotador.recebidos).isEmpty();
        assertThat(lerDeNovo()).hasSize(1);
    }

    @Test
    @DisplayName("Deve manter na fila a mensagem malformada")
    void deveManterMensagemMalformada() throws Exception {
        enviar("isto não é json");

        consumidor.consumir();

        assertThat(anotador.recebidos).isEmpty();
        assertThat(lerDeNovo()).hasSize(1);
    }

    private MessageEnvelope envelope(String tipo) {
        UUID osId = UUID.randomUUID();
        return new MessageEnvelope(UUID.randomUUID(), tipo, 1, UUID.randomUUID(), osId, "req-123",
                UUID.randomUUID(), Instant.parse("2026-11-15T19:00:00Z"),
                mapper.valueToTree(Map.of("osId", osId)));
    }

    private void enviar(String corpo) {
        sqs.sendMessage(r -> r.queueUrl(filaUrl).messageBody(corpo));
    }

    /**
     * Espera a visibilidade (1 s) passar e lê a fila de novo.
     */
    private List<Message> lerDeNovo() throws InterruptedException {
        Thread.sleep(1500);
        return sqs.receiveMessage(r -> r.queueUrl(filaUrl).waitTimeSeconds(1)).messages();
    }

    private static class TratadorQueAnota implements TratadorDeEventos {
        private final List<EventoRecebido> recebidos = new ArrayList<>();

        @Override
        public String tipo() {
            return "PecasReservadas";
        }

        @Override
        public void tratar(EventoRecebido evento) {
            recebidos.add(evento);
        }
    }

    private static class TratadorQueFalha implements TratadorDeEventos {
        @Override
        public String tipo() {
            return "ReservaPecasRecusada";
        }

        @Override
        public void tratar(EventoRecebido evento) {
            throw new IllegalStateException("falha simulada");
        }
    }
}