DROP TABLE IF EXISTS alocacao_pecas;
DROP TABLE IF EXISTS prestacao_servicos;
DROP TABLE IF EXISTS pecas;
ALTER TABLE ordem_servicos DROP COLUMN IF EXISTS valor_total;
ALTER TABLE ordem_servicos DROP COLUMN IF EXISTS pago;
