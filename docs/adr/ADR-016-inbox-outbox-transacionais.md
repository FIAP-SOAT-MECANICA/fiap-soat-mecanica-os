# ADR-016: Inbox e outbox transacionais no PostgreSQL

- **Status:** Aceito
- **Data:** 2026-11-17
- **Repositório:** fiap-soat-mecanica-os
- **Implementação:** `V11__create_mensagem_processada.sql`, `V12__create_outbox.sql`, `ProcessadorDeEventos`, `InboxRepositoryImpl`, `OutboxRepositoryImpl`, `PublicadorDaOutbox`, `config/observability/RastreamentoMensagens`
- **Relacionados:** ADR-004, ADR-012, ADR-013, ADR-015

## Contexto

Dois problemas clássicos de mensageria aparecem assim que a saga muda o estado da OS e manda um
comando:

1. **Escrita dupla.** Gravar no PostgreSQL e publicar no SQS são duas operações sem transação comum.
   Publicar antes do commit manda um comando de um estado que pode sofrer rollback; publicar depois
   perde o comando se o pod cair entre um e outro.
2. **Entrega pelo menos uma vez.** O SQS e as outboxes do Execução e do Billing podem entregar a mesma
   mensagem de novo. Reaplicar `PecasReservadas` mandaria `IniciarExecucao` duas vezes.

## Decisão

**Outbox.** O orquestrador nunca publica. Ele grava a mensagem na tabela `outbox` na mesma transação
da OS e da saga. Um job agendado (`PublicadorDaOutbox`, a cada 500 ms) lê as pendentes com
`FOR UPDATE SKIP LOCKED` (duas réplicas não pegam a mesma linha), publica e marca `publicado_em`.
O `id` da linha é o `messageId` do envelope: se o pod cair depois de publicar e antes de marcar, a
linha é publicada de novo **com o mesmo messageId**, e o consumidor deduplica. Falha de publicação
incrementa `tentativas`, guarda `ultimo_erro` e não bloqueia as outras mensagens do lote.

**Inbox.** Todo evento consumido passa pelo `ProcessadorDeEventos`, que, numa transação, faz
`INSERT INTO mensagem_processada ... ON CONFLICT (message_id) DO NOTHING` e só chama o tratador se
inseriu uma linha. Se o tratador falha, o rollback desfaz também a inbox e a reentrega processa de
novo. O consumidor só apaga a mensagem do SQS depois do commit. `INSERT ... ON CONFLICT` em vez de
`SELECT` seguido de `INSERT`: dois consumidores com a mesma mensagem não passam juntos.

**Rastreamento.** A outbox quebra a cadeia de contexto do OpenTelemetry (quem publica é um job, não
a requisição). O `traceparent` W3C do contexto atual é gravado na linha, vai como atributo da
mensagem SQS e é reativado no consumidor como span filho, para o Datadog mostrar um rastro só.

**Acesso a dados.** Inbox, outbox e saga usam `JdbcClient` (JDBC) e não JPA: `ON CONFLICT`,
`FOR UPDATE SKIP LOCKED` e colunas `JSONB` ficam explícitos. O `JdbcClient` participa da transação
do `JpaTransactionManager`, então tudo é atômico com as entidades JPA. `OrdemServicoRepositoryImpl`
passou a usar `saveAndFlush` porque a saga (JDBC) tem FK para `ordem_servicos`.

## Consequências

**Positivas**
- Nenhum comando sai de um estado que não foi confirmado, e nenhum se perde.
- Efeito aplicado uma vez mesmo com reentrega (testado em `ProcessadorDeEventosIntegrationTest` e
  `PublicadorDaOutboxTest`).
- Com o publicador desligado as mensagens se acumulam na tabela, sem perda.

**Negativas / riscos**
- Latência extra de até um ciclo do publicador por passo da saga.
- `mensagem_processada` e `outbox` crescem sem limite; falta um job de limpeza (linhas mais velhas
  que a retenção do SQS e da DLQ, 14 dias, podem sair).
- O Execução não deduplica `IniciarExecucao` por `messageId` (só por ciclo de reserva); uma
  republicação depende da idempotência do lado dele.

## Registros desta fase

- A `V10` derrubou `pecas`, `prestacao_servicos` e `alocacao_pecas`. Os dados existentes eram de teste
  e foram descartados; o estoque real nasce no Execução.
- O ADR-005 cita `infra/variables.tf`; o Terraform do Kind foi movido para `infra/k8s/` na 4.1, ao
  lado de `infra/rds/`, `infra/messaging/` e `infra/historico/`. O ADR-005 não foi editado (ADRs
  aceitos não mudam).
- O ADR-004 continua valendo para o e-mail: ele não passa pela outbox e segue sem retry.
