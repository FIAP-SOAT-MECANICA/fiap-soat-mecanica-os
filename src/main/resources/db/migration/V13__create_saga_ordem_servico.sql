-- Instância da saga orquestrada (tarefa 6.1). Uma saga por OS.
-- É a tabela a mostrar no vídeo: passo atual, estado, passos concluídos e compensados.
CREATE TABLE saga_ordem_servico
(
    saga_id            UUID PRIMARY KEY,
    os_id              UUID        NOT NULL UNIQUE,
    passo_atual        VARCHAR(30) NOT NULL,
    estado             VARCHAR(30) NOT NULL,
    passos_concluidos  JSONB       NOT NULL DEFAULT '[]',
    passos_compensados JSONB       NOT NULL DEFAULT '[]',
    itens_diagnostico  JSONB,
    orcamento_id       UUID,
    -- cycleId exigido pelo Execução em ReservarPecas/LiberarReservaPecas/IniciarExecucao
    ciclo_reserva_id   UUID,
    motivo             TEXT,
    criado_em          TIMESTAMPTZ NOT NULL,
    atualizado_em      TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_saga_ordem_servico
        FOREIGN KEY (os_id)
            REFERENCES ordem_servicos (id)
);
