package br.com.fiap.soat.mecanica.application.saga;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    /**
     * Grava a mensagem na transação de quem chama. Só sai do banco depois do commit.
     *
     * @return o messageId que a mensagem vai ter no envelope
     */
    UUID registrar(MensagemSaida mensagem);

    /**
     * Pendentes em ordem de criação, travadas para esta transação (FOR UPDATE SKIP LOCKED):
     * duas réplicas do pod não pegam a mesma linha.
     */
    List<MensagemOutbox> travarPendentes(int limite);

    void marcarPublicada(UUID id);

    void registrarFalha(UUID id, String erro);
}
