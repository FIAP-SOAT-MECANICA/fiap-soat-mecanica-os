-- Outbox transacional (tarefa 5.5).
-- A mudança de estado da OS/saga e a mensagem que ela gera são gravadas na mesma transação.
-- Um publicador agendado lê as pendentes, publica e marca publicado_em.
-- id é o messageId do envelope: republicar após falha reusa o mesmo id, e o consumidor deduplica.
CREATE TABLE outbox
(
    id             UUID PRIMARY KEY,
    destino        VARCHAR(30)  NOT NULL,
    tipo           VARCHAR(100) NOT NULL,
    saga_id        UUID         NOT NULL,
    os_id          UUID         NOT NULL,
    correlation_id VARCHAR(200),
    causation_id   UUID,
    payload        JSONB        NOT NULL,
    traceparent    VARCHAR(100),
    criado_em      TIMESTAMPTZ  NOT NULL,
    publicado_em   TIMESTAMPTZ,
    tentativas     INT          NOT NULL DEFAULT 0,
    ultimo_erro    TEXT
);

-- O publicador só olha as pendentes; o índice parcial fica pequeno mesmo com a tabela crescendo.
CREATE INDEX idx_outbox_pendentes ON outbox (criado_em) WHERE publicado_em IS NULL;
