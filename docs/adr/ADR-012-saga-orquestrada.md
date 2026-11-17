# ADR-012: Saga orquestrada, com o orquestrador no OS Service

- **Status:** Aceito
- **Data:** 2026-11-17
- **Repositório:** fiap-soat-mecanica-os
- **Implementação:** `application/saga/OrquestradorSaga`, `domain/saga/SagaOrdemServico`, tabela `saga_ordem_servico` (`V13`)
- **Relacionados:** RFC-002, ADR-013, ADR-016

## Contexto

Na Fase 4 a OS atravessa três serviços com bancos próprios: o OS (PostgreSQL), o Execução
(DynamoDB: diagnóstico, estoque, execução) e o Billing (PostgreSQL: orçamento, cobrança). Reservar
peças e cancelar um orçamento acontecem em bancos diferentes; não existe transação que desfaça os
dois. Uma falha no meio do fluxo precisa ser desfeita por **compensações**, uma por passo.

Havia duas formas de coordenar: coreografia (cada serviço reage aos eventos dos outros; o README do
Billing chegou a propor consumir `DiagnosticoConcluido` direto do Execução) ou orquestração (um
serviço decide o próximo passo e manda comandos).

## Decisão

- **Orquestração, com o orquestrador dentro do OS.** O OS é o único serviço que conhece o fluxo
  inteiro e o único que fala com os outros dois. Execução e Billing não se conhecem.
- Cada OS tem uma instância de saga com `passo_atual`, `estado` (`EM_ANDAMENTO`, `CONCLUIDA`,
  `CANCELADA`, `COMPENSADA`), `passos_concluidos` e `passos_compensados`. É a tabela mostrada no
  vídeo e exposta em `GET /ordem-servicos/{id}/saga`.
- Cada evento recebido é validado contra o passo atual:
  - do passo atual: avança, grava OS + saga + próximos comandos na mesma transação (outbox, ADR-016);
  - de um passo **à frente**: chegou fora de ordem (SNS + SQS padrão não garantem ordem); a
    transação é desfeita e a mensagem volta para a fila (`EventoForaDeOrdemException`);
  - de um passo **já passado** ou com a saga encerrada: atrasado ou repetido; registra em log e ignora.
- A saga é lida com `SELECT ... FOR UPDATE`: dois eventos da mesma OS em réplicas diferentes são
  serializados.
- Compensações implementadas:

  | Falha | Compensação |
  | --- | --- |
  | `OrcamentoRecusado` | OS `CANCELADA` (não há reserva a desfazer) |
  | `ReservaPecasRecusada` | `CancelarOrcamento` (Billing); OS continua `AGUARDANDO_APROVACAO`; saga `COMPENSADA` |
  | Desistência (`PATCH /cancelar`) | em ordem inversa: `LiberarReservaPecas` (Execução) se havia reserva feita ou em voo, `CancelarOrcamento` (Billing) se o orçamento foi pedido, OS `CANCELADA` |
  | `PagamentoRecusado` | `CancelarCobranca`; OS fica `FINALIZADA`; saga `COMPENSADA` |

- A decisão de liberar peças é tomada pelo **passo da saga**, não pela situação da OS:
  `AGUARDANDO_APROVACAO` existe com e sem reserva. `PecasReservadas` que chega depois do
  cancelamento gera um novo `LiberarReservaPecas` do mesmo `cycleId`.
- O orquestrador manda o `cycleId` exigido pelo Execução em `ReservarPecas`, `LiberarReservaPecas`
  e `IniciarExecucao`.

## Consequências

**Positivas**
- O estado de qualquer OS no fluxo está numa linha de tabela, legível sem correlacionar logs de três
  serviços.
- O Billing recebe `clienteId` e `nomeServico` no `GerarOrcamento`, dados que só o OS tem: na
  coreografia o Billing precisaria consultar o OS ou duplicar dados.
- Compensações explícitas e testadas (cenários BDD em `saga-ordem-servico.feature`).

**Negativas / riscos**
- O OS vira dependência de todos e concentra a lógica do fluxo.
- Sem rota manual de transição (decisão C7 da 1.2): uma saga que fique parada (evento perdido na DLQ)
  só anda com reprocessamento da DLQ ou intervenção no banco.
- A desistência nos passos 1 e 2 não fecha o diagnóstico no Execução: não existe comando para isso
  no contrato atual.
- `OrcamentoRecusado` cancela a OS em vez de voltar para `EM_DIAGNOSTICO` como previa a RFC-002:
  o Execução não reabre diagnóstico concluído, então voltar deixaria a saga sem evento que a
  fizesse andar.
- OS só com mão de obra pula a reserva e manda `IniciarExecucao` sem `cycleId`; o Execução declara
  que esse caso ainda precisa de fluxo próprio.
