# ADR-015: Histórico da OS em DynamoDB, alimentado pela outbox

- **Status:** Aceito
- **Data:** 2026-11-17
- **Repositório:** fiap-soat-mecanica-os
- **Implementação:** `infra/historico/`, `adapters/out/historico/DynamoHistoricoOrdemServicoRepository`, `GET /ordem-servicos/{id}/historico`
- **Relacionados:** ADR-012, ADR-016

## Contexto

Cada OS passa por várias situações (`RECEBIDA` até `ENTREGUE` ou `CANCELADA`) e o enunciado pede o
histórico dessas transições. O padrão de acesso é simples: escrita a cada transição, leitura de todas
as transições de **uma** OS, em ordem cronológica. Nunca há junção com outras tabelas.

## Decisão

- Tabela DynamoDB `os-historico`, sob demanda, **PK `osId`**, **SK `ocorridoEm`** no formato
  `"<ISO-8601 UTC com milissegundos>#<messageId>"`. O texto ISO de tamanho fixo ordena igual ao
  tempo; o `messageId` impede que duas transições no mesmo milissegundo se sobrescrevam.
- A transição **não** escreve no DynamoDB. Ela grava uma linha `HISTORICO` na outbox, na mesma
  transação da mudança de situação; o publicador da outbox faz o `PutItem`.
- O `PutItem` é condicional (`attribute_not_exists(ocorridoEm)`): republicar a mesma linha da outbox
  não duplica o histórico.
- Leitura por `Query` na partição, `ScanIndexForward = true`, paginada.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Tabela de histórico no PostgreSQL do OS | Funcionaria; mas o histórico é só escrita e leitura por chave, cresce sem parar e não precisa de transação com nada. Tirar do RDS deixa o banco transacional menor |
| Escrever no DynamoDB direto na transição | O DynamoDB não participa da transação do PostgreSQL: rollback da transição deixaria uma entrada falsa no histórico, ou o inverso |
| Event sourcing (histórico como fonte da verdade) | Muda o modelo de toda a OS; desproporcional ao escopo |

## Consequências

**Positivas**
- Histórico nunca mente: só existe entrada para transição confirmada no PostgreSQL.
- Escrita e leitura em tempo constante, sem índice nem manutenção, e custo por requisição.
- Estado da saga e inbox continuam no PostgreSQL porque **precisam** ser atômicos com a OS; o
  histórico é derivado e pode chegar alguns instantes depois.

**Negativas / riscos**
- Consistência eventual: logo após uma transição o endpoint pode ainda não mostrá-la (intervalo do
  publicador, 500 ms por padrão).
- Com o publicador desligado (Kind, testes) o histórico não é preenchido; as entradas ficam pendentes
  na outbox e não se perdem.
- Ordem entre transições no mesmo milissegundo é pelo `messageId`, não pela ordem real.
