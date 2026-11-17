package br.com.fiap.soat.mecanica.application.saga;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Linha pendente da outbox, pronta para publicar. {@code id} é o messageId do envelope:
 * republicar a mesma linha reusa o mesmo id e o consumidor deduplica.
 */
public record MensagemOutbox(
        UUID id,
        DestinoMensagem destino,
        String tipo,
        UUID sagaId,
        UUID osId,
        String correlationId,
        UUID causationId,
        JsonNode payload,
        String traceparent,
        Instant criadoEm
) {
}
