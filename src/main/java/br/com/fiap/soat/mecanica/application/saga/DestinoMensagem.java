package br.com.fiap.soat.mecanica.application.saga;

/**
 * Para onde vai uma mensagem gravada na outbox.
 * EXECUCAO e BILLING viram comandos SQS; HISTORICO vira um item na tabela os-historico (DynamoDB).
 */
public enum DestinoMensagem {

    EXECUCAO,
    BILLING,
    HISTORICO
}
