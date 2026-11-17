package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.exception.MensagemInvalidaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JsonTest
class MessageEnvelopeTest {

    private static final String FIXTURE = "/messaging/diagnostico-concluido.json";
    private static final UUID OS_ID = UUID.fromString("2d8b4e3a-5f6c-4b7d-8e9f-a0b1c2d3e4f5");

    @Autowired
    private ObjectMapper mapper;

    private String jsonOriginal;
    private MessageEnvelope envelope;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            jsonOriginal = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        envelope = mapper.readValue(jsonOriginal, MessageEnvelope.class);
    }

    @Test
    @DisplayName("Deve ler o envelope real do Execução com todos os campos")
    void deveLerTodosOsCampos() {
        assertThat(envelope.messageId()).isEqualTo(UUID.fromString("0b6f2c1e-3d4a-4f5b-8c6d-7e8f9a0b1c2d"));
        assertThat(envelope.type()).isEqualTo("DiagnosticoConcluido");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.sagaId()).isEqualTo(UUID.fromString("1c7a3d2f-4e5b-4a6c-9d7e-8f9a0b1c2d3e"));
        assertThat(envelope.osId()).isEqualTo(OS_ID);
        assertThat(envelope.correlationId()).isEqualTo("req-2026-11-15-0001");
        assertThat(envelope.causationId()).isNull();
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-11-15T19:00:00Z"));
        assertThat(envelope.payload().get("itens").get(0).get("pecas").get(0).get("qtd").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Deve serializar de volta sem perder nenhum campo")
    void deveFazerIdaEVoltaSemPerderCampo() throws IOException {
        String jsonGerado = mapper.writeValueAsString(envelope);

        JsonNode original = mapper.readTree(jsonOriginal);
        JsonNode gerado = mapper.readTree(jsonGerado);
        assertThat(gerado).isEqualTo(original);
    }

    @Test
    @DisplayName("Deve ignorar campo novo que o Execução venha a mandar")
    void deveIgnorarCampoDesconhecido() throws IOException {
        String comCampoNovo = jsonOriginal.replaceFirst("\\{", "{ \"campoNovo\": \"x\",");

        MessageEnvelope lido = mapper.readValue(comCampoNovo, MessageEnvelope.class);

        assertThat(lido.messageId()).isEqualTo(envelope.messageId());
    }

    @Test
    @DisplayName("Deve aceitar o envelope válido")
    void deveAceitarEnvelopeValido() {
        assertThatCode(envelope::validar).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Deve recusar versão diferente de 1")
    void deveRecusarVersaoDiferente() {
        MessageEnvelope v2 = new MessageEnvelope(envelope.messageId(), envelope.type(), 2, envelope.sagaId(),
                envelope.osId(), envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatThrownBy(v2::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar messageId nulo")
    void deveRecusarMessageIdNulo() {
        MessageEnvelope semId = new MessageEnvelope(null, envelope.type(), 1, envelope.sagaId(),
                envelope.osId(), envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatThrownBy(semId::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar type nulo")
    void deveRecusarTypeNulo() {
        MessageEnvelope semId = new MessageEnvelope(envelope.messageId(), null, 1, envelope.sagaId(),
                envelope.osId(), envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatThrownBy(semId::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar sagaId nulo")
    void deveRecusarSagaIdNulo() {
        MessageEnvelope semSaga = new MessageEnvelope(envelope.messageId(), envelope.type(), 1, null,
                envelope.osId(), envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatThrownBy(semSaga::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Na entrada aceita sagaId nulo (eventos do Billing), mas mantém as demais regras")
    void deveAceitarSagaIdNuloNaEntrada() {
        MessageEnvelope semSaga = new MessageEnvelope(envelope.messageId(), envelope.type(), 1, null,
                envelope.osId(), envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());
        MessageEnvelope semOs = new MessageEnvelope(envelope.messageId(), envelope.type(), 1, null,
                null, envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatCode(semSaga::validarEntrada).doesNotThrowAnyException();
        assertThatThrownBy(semOs::validarEntrada).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar osId nulo")
    void deveRecusarOsIdNulo() {
        MessageEnvelope semId = new MessageEnvelope(envelope.messageId(), envelope.type(), 1, envelope.sagaId(),
                null, envelope.correlationId(), envelope.causationId(), envelope.occurredAt(),
                envelope.payload());

        assertThatThrownBy(semId::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar payload nulo")
    void deveRecusarPayloadNulo() {
        MessageEnvelope semPayload = new MessageEnvelope(envelope.messageId(), envelope.type(), 1,
                envelope.sagaId(), envelope.osId(), envelope.correlationId(), envelope.causationId(),
                envelope.occurredAt(), null);

        assertThatThrownBy(semPayload::validar).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    @DisplayName("Deve recusar payload.osId diferente do osId do envelope")
    void deveRecusarOsIdDivergente() {
        MessageEnvelope outraOs = new MessageEnvelope(envelope.messageId(), envelope.type(), 1,
                envelope.sagaId(), UUID.randomUUID(), envelope.correlationId(), envelope.causationId(),
                envelope.occurredAt(), envelope.payload());

        assertThatThrownBy(outraOs::validar).isInstanceOf(MensagemInvalidaException.class);
    }
}