# ADR-014: JSON com envelope versionado, e não Avro

- **Status:** Aceito
- **Data:** 2026-11-17
- **Repositório:** fiap-soat-mecanica-os
- **Implementação:** `adapters/out/messaging/MessageEnvelope`, `application/saga/contrato/Payloads`
- **Relacionados:** RFC-002, ADR-013

## Contexto

As mensagens entre OS, Execução e Billing precisam de um formato comum. Três grupos escrevem os
serviços em paralelo, e o Execução já publicava em JSON. Não há registro de schema disponível
(Confluent Schema Registry ou AWS Glue Schema Registry) no ambiente.

## Decisão

- **JSON, serializado com Jackson**, num envelope comum que não se altera:
  `messageId`, `type`, `version`, `sagaId`, `osId`, `correlationId`, `causationId`, `occurredAt`,
  `payload`.
- **Versão no envelope** (`version = 1`). O Execução rejeita outra versão; o OS também. Mudança
  incompatível de payload vira `version = 2`, não alteração silenciosa.
- Validação explícita no código (`MessageEnvelope.validar()` para o que o OS envia, com as mesmas
  regras do Execução; `validarEntrada()` para o que recebe, que aceita `sagaId` nulo porque o Billing
  ainda não o devolve).
- Evolução compatível por **campo novo opcional**: o leitor ignora campo desconhecido
  (`FAIL_ON_UNKNOWN_PROPERTIES` desligado) e o escritor omite campo nulo quando faz sentido
  (`cycleId`).

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Avro com Schema Registry | Sem registro no ambiente; sem registro, o schema teria que viajar em cada mensagem ou ser combinado fora de banda, e o ganho de tamanho do binário não se paga em mensagens de poucos KB |
| Protobuf | Mesmo custo de geração de código e distribuição de `.proto` entre três repositórios, para o mesmo ganho pequeno |
| JSON Schema publicado | Útil como documentação; ficou para depois, o contrato está na RFC-002 |

## Consequências

**Positivas**
- Legível no console do SQS, em log e na DLQ: depurar uma saga é ler a mensagem.
- Sem dependência de serviço externo nem de etapa de geração de código.
- Compatível com o que o Execução já produzia.

**Negativas / riscos**
- Contrato verificado só em tempo de execução: um campo renomeado por outro grupo vira
  `MensagemInvalidaException` ou payload nulo, e a mensagem acaba na DLQ. Mitigação: contrato
  escrito na RFC-002 e testes que leem um envelope real do Execução
  (`src/test/resources/messaging/diagnostico-concluido.json`).
- Mensagens maiores que no binário; irrelevante no volume do projeto (limite do SQS: 256 KB).
