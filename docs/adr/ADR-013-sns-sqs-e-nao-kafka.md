# ADR-013: SNS para eventos e SQS para comandos, e não Kafka

- **Status:** Aceito
- **Data:** 2026-11-17
- **Repositório:** fiap-soat-mecanica-os
- **Implementação:** `infra/messaging/`, `adapters/out/messaging/SqsPublicadorDeComandos`, `adapters/in/messaging/SqsConsumidorDeRespostas`
- **Relacionados:** RFC-002, ADR-012, ADR-016

## Contexto

Os três serviços precisam trocar comandos (OS → Execução, OS → Billing) e eventos
(Execução → OS, Billing → OS) de forma assíncrona: se o Billing cair, a OS não pode travar no meio
do fluxo, e a mensagem tem que esperar. O ambiente é a AWS Academy, recriada a cada sessão, com
orçamento e permissões limitados. O Execução já estava pronto usando SNS e SQS.

## Decisão

- **Comandos vão para uma fila SQS do serviço dono** (`mecanica-execucao-comandos`,
  `mecanica-billing-comandos`). Comando tem um destinatário só.
- **Eventos são publicados em tópicos SNS** (`mecanica-execucao-eventos`, `mecanica-billing-eventos`,
  `mecanica-os-eventos`). Quem quer ouvir assina com a própria fila. O OS é dono das assinaturas
  dele: os dois tópicos entregam na fila `mecanica-os-respostas`, com `raw_message_delivery` (o
  corpo chega como o envelope, sem o invólucro do SNS).
- Fila de respostas com **DLQ após 5 recebimentos**, visibilidade de 60 s e long polling de 20 s.
- Descoberta de recursos pelo SSM (`/mecanica/<servico>/command-queue-url`, `.../event-topic-arn`),
  lido pelo Terraform de quem consome. Toda a infraestrutura é Terraform.
- Uma fila de respostas só para Execução e Billing: um consumidor, uma inbox, uma DLQ. O roteamento
  é pelo `type` do envelope.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Kafka (Amazon MSK) | Cluster pago por hora, sem camada gratuita que caiba na Academy; operação de brokers, partições e consumer groups sem ganho para o volume de uma oficina; o Execução já estava em SNS/SQS |
| Só SQS (sem SNS) | O Execução teria que conhecer a fila de cada interessado; um segundo consumidor de eventos exigiria mudar o produtor |
| REST síncrono entre serviços | Acopla a disponibilidade: Billing fora do ar parava a OS sem retentativa |
| EventBridge | Bom para roteamento por conteúdo, mas sem ganho aqui sobre SNS e fora do que o Execução já usa |

## Consequências

**Positivas**
- Serviço gerenciado, sem servidor para manter; custo praticamente zero no volume do projeto.
- Retentativa e DLQ nativas: mensagem que falha não é apagada e volta sozinha.
- Adicionar um consumidor de eventos é criar uma assinatura, sem tocar no produtor.

**Negativas / riscos**
- Entrega **pelo menos uma vez**: todo consumidor deduplica por `messageId` (inbox, ADR-016).
- Fila padrão **não garante ordem**: o orquestrador trata evento adiantado devolvendo-o à fila
  (ADR-012). Fila FIFO resolveria a ordem por OS, mas SNS FIFO exige tópico FIFO no produtor,
  que o Execução não usa.
- Sem replay do histórico de eventos como no Kafka; a DLQ guarda 14 dias.
