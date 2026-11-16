package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.exception.MensagemInvalidaException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record MessageEnvelope(
    UUID messageId,
    String type,
    int version,
    UUID sagaId,
    UUID osId,
    String correlationId,
    UUID causationId,
    Instant occurredAt,
    JsonNode payload
) {

    public static final int VERSAO_SUPORTADA = 1;

    public void validar() {
        if (version != VERSAO_SUPORTADA) {
            throw new MensagemInvalidaException("Versão de envelope não suportada: " + version);
        }
        exigir(messageId, "messageId");
        exigir(sagaId, "sagaId");
        exigir(osId, "osId");
        exigir(payload, "payload");
        if (type == null || type.isBlank()) {
            throw new MensagemInvalidaException("Campo obrigatório ausente: type");
        }

        JsonNode osIdDoPayload = payload.get("osId");
        if (osIdDoPayload == null || !osId.toString().equals(osIdDoPayload.asText())) {
            throw new MensagemInvalidaException("payload.osId diferente de osId do envelope");
        }
    }

    private static void exigir(Object valor, String campo) {
        if (valor == null) {
            throw new MensagemInvalidaException("Campo obrigatório ausente: " + campo);
        }
    }
}
