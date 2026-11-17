-- Inbox de idempotência (tarefa 5.4).
-- Cada evento consumido grava seu messageId aqui, na mesma transação do efeito.
-- Reentrega do SQS cai no ON CONFLICT e é descartada sem reaplicar o efeito.
CREATE TABLE mensagem_processada
(
    message_id    UUID PRIMARY KEY,
    tipo          VARCHAR(100) NOT NULL,
    processada_em TIMESTAMPTZ  NOT NULL DEFAULT now()
);
