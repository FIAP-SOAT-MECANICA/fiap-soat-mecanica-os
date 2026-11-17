package br.com.fiap.soat.mecanica.application.saga;

import java.util.UUID;

/**
 * Mensagem que o orquestrador quer enviar. Não é enviada na hora: vai para a outbox,
 * na mesma transação da mudança de estado, e o publicador da outbox entrega depois.
 */
public record MensagemSaida(
        DestinoMensagem destino,
        String tipo,
        UUID sagaId,
        UUID osId,
        String correlationId,
        UUID causationId,
        Object payload
) {
}
