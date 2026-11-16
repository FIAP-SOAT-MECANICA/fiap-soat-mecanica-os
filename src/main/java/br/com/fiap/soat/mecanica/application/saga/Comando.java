package br.com.fiap.soat.mecanica.application.saga;

import java.util.UUID;

public record Comando(
        DestinoComando destino,
        String tipo,
        UUID sagaId,
        UUID osId,
        String correlationId,
        UUID causationId,
        Object payload
) {
}
