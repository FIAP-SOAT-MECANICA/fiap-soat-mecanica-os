package br.com.fiap.soat.mecanica.application.ordemServico.historico;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma transição de situação da OS. {@code messageId} é o id da linha da outbox que a gerou:
 * entra na chave de ordenação e torna a gravação idempotente.
 */
public record EntradaHistorico(
        UUID osId,
        Instant ocorridoEm,
        UUID messageId,
        String situacaoAnterior,
        String situacaoNova,
        String evento
) {
}
