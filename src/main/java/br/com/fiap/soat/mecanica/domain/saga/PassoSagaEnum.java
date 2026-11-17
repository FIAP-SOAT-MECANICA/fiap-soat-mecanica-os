package br.com.fiap.soat.mecanica.domain.saga;

/** Passos da saga, na ordem da tabela da RFC-002. A ordem do enum é usada para detectar evento adiantado. */
public enum PassoSagaEnum {
    ABERTURA,
    DIAGNOSTICO,
    ORCAMENTO,
    RESERVA,
    EXECUCAO,
    COBRANCA,
    ENTREGA
}
