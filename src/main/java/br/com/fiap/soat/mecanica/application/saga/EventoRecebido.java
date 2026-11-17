package br.com.fiap.soat.mecanica.application.saga;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record EventoRecebido(
        UUID messageId,
        String type,
        UUID sagaId,
        UUID osId,
        String correlationId,
        UUID causationId,
        JsonNode payload
) {
}
