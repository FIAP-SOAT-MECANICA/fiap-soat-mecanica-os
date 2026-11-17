package br.com.fiap.soat.mecanica.domain.saga;

public enum EstadoSagaEnum {
    /** Esperando a resposta do passo atual. */
    EM_ANDAMENTO,
    /** Chegou à entrega. */
    CONCLUIDA,
    /** OS cancelada (desistência ou orçamento recusado); compensações enviadas. */
    CANCELADA,
    /** Um passo falhou (sem estoque, pagamento recusado); compensações enviadas e a saga parou. */
    COMPENSADA
}
