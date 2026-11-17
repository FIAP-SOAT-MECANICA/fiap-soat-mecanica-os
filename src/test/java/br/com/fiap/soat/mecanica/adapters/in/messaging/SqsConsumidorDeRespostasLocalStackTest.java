package br.com.fiap.soat.mecanica.adapters.in.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.MessageEnvelope;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.InboxRepository;
import br.com.fiap.soat.mecanica.application.saga.ProcessadorDeEventos;
import br.com.fiap.soat.mecanica.application.saga.TratadorDeEventos;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.opentelemetry.api.trace.Span;
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
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqsConsumidorDeRespostasLocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.9.2"))
            .withServices(LocalStackContainer.Service.SQS);

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

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
                new ProcessadorDeEventos(new InboxEmMemoria(), List.of(anotador, new TratadorQueFalha())));
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
        assertThat(evento.correlationId()).isEqualTo("req-123");
        assertThat(evento.payload().get("osId").asText()).isEqualTo(envelope.osId().toString());
        assertThat(lerDeNovo()).isEmpty();
    }

    @Test
    @DisplayName("Deve apagar a mensagem duplicada sem tratar de novo")
    void deveApagarDuplicadaSemTratar() throws Exception {
        String corpo = mapper.writeValueAsString(envelope("PecasReservadas"));
        enviar(corpo);
        enviar(corpo); // mesma mensagem duas vezes, como numa reentrega do SQS

        consumidor.consumir();

        assertThat(anotador.recebidos).hasSize(1);
        assertThat(lerDeNovo()).isEmpty();
    }

    @Test
    @DisplayName("Deve aceitar evento do Billing sem sagaId")
    void deveAceitarEventoSemSagaId() throws Exception {
        MessageEnvelope envelope = envelope("PecasReservadas");
        MessageEnvelope semSaga = new MessageEnvelope(envelope.messageId(), envelope.type(), 1, null,
                envelope.osId(), null, null, envelope.occurredAt(), envelope.payload());
        enviar(mapper.writeValueAsString(semSaga));

        consumidor.consumir();

        assertThat(anotador.recebidos).hasSize(1);
        assertThat(anotador.recebidos.get(0).sagaId()).isNull();
    }

    @Test
    @DisplayName("Deve reativar o rastro do produtor a partir do atributo traceparent (7.1)")
    void deveReativarRastro() throws Exception {
        String traceparent = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
        String corpo = mapper.writeValueAsString(envelope("PecasReservadas"));
        sqs.sendMessage(r -> r.queueUrl(filaUrl)
                .messageBody(corpo)
                .messageAttributes(Map.of(RastreamentoMensagens.ATRIBUTO, MessageAttributeValue.builder()
                        .dataType("String").stringValue(traceparent).build())));

        consumidor.consumir();

        assertThat(anotador.traceIds).containsExactly(TRACE_ID);
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

    private static class InboxEmMemoria implements InboxRepository {
        private final Set<UUID> vistas = ConcurrentHashMap.newKeySet();

        @Override
        public boolean registrarSeNova(UUID messageId, String tipo) {
            return vistas.add(messageId);
        }
    }

    private static class TratadorQueAnota implements TratadorDeEventos {
        private final List<EventoRecebido> recebidos = new ArrayList<>();
        private final List<String> traceIds = new ArrayList<>();

        @Override
        public Set<String> tipos() {
            return Set.of("PecasReservadas");
        }

        @Override
        public void tratar(EventoRecebido evento) {
            recebidos.add(evento);
            traceIds.add(Span.current().getSpanContext().getTraceId());
        }
    }

    private static class TratadorQueFalha implements TratadorDeEventos {
        @Override
        public Set<String> tipos() {
            return Set.of("ReservaPecasRecusada");
        }

        @Override
        public void tratar(EventoRecebido evento) {
            throw new IllegalStateException("falha simulada");
        }
    }
}
