# RFC-002: Decomposição do monolito e o OS Service como orquestrador da Saga

- **Status:** Proposto
- **Data:** 2026-11-13
- **Autoria:** Gabriel Sartoretto
- **Repositório:** fiap-soat-mecanica-os

## Resumo

Na Fase 4 a aplicação da oficina deixa de ser um monolito e passa a ser três
microsserviços, cada um com repositório, infraestrutura e banco próprios:
**OS Service** (este repositório), **Execução** e **Billing**. Eles conversam
apenas por mensageria assíncrona (SNS para eventos, SQS para comandos, JSON)
ou por chamada REST explícita, nunca pelo banco um do outro. O fluxo da ordem
de serviço, do recebimento à entrega, é coordenado por uma **Saga
orquestrada** cujo orquestrador mora aqui: o OS Service é o único que fala
com os outros dois, decide o próximo passo e dispara as compensações quando
um passo falha. Esta RFC registra as fronteiras de cada serviço, o contrato
de mensagens, a organização do código e as alternativas avaliadas. Os
detalhes de implementação (outbox, inbox, tabela da saga) ficam para ADRs
próprios, escritos quando forem implementados.

## Motivação

Até a Fase 3 um único processo cuidava de cadastro, ordem de serviço,
estoque de peças, execução do reparo, orçamento e pagamento, tudo no mesmo
PostgreSQL. A Fase 4 pede serviços independentes, com bancos separados, e um
fluxo transacional que atravesse esses serviços com consistência.

Com bancos separados, uma única transação de banco deixa de cobrir o fluxo.
Reservar peças (Execução) e cancelar um orçamento (Billing) acontecem em
bancos diferentes, e não existe `ROLLBACK` que desfaça os dois. A Saga
resolve isso trocando a transação global por uma sequência de transações
locais, em que cada passo tem uma **compensação** que desfaz seu efeito de
negócio quando um passo seguinte falha.

## Proposta

### 1. Fronteiras: o que fica, o que sai

| Serviço | Responsabilidade | Banco |
| --- | --- | --- |
| **OS Service** (este) | Ciclo de vida da `OrdemServico`; cadastros de `Cliente`, `Veiculo`, `Usuario` e `Servico`; orquestrador da Saga; histórico de transições da OS | PostgreSQL (RDS) + DynamoDB (`os-historico`) |
| Execução (`fiap-soat-mecanica-execucao`) | Diagnóstico, catálogo e estoque de peças (`peca`), reserva de peças (`alocacaoPeca`), execução do reparo (`prestacaoServico`) | DynamoDB |
| Billing (`fiap-soat-mecanica-billing`) | Geração e cálculo do orçamento, aprovação e recusa pelo cliente, cobrança e pagamento | PostgreSQL |

Consequências para este repositório:

- Saem os domínios `peca`, `prestacaoServico` e `alocacaoPeca`, e todo o
  fluxo de orçamento e pagamento, incluindo os endpoints
  `PATCH /ordem-servicos/{id}/aprovar-orcamento` e `recusar-orcamento`.
- A OS continua tendo a situação `AGUARDANDO_APROVACAO`, mas quem gera o
  orçamento e recebe a decisão do cliente é o Billing. O OS só reage ao
  evento e muda de situação.
- `Servico` fica aqui. O Execução chama `GET /servicos/{id}` para validar e
  cachear o nome do serviço.
- Entra a situação terminal `CANCELADA`, que a Saga precisa e o enum de hoje
  não tem.

### 2. Cada serviço é dono dos próprios dados

**Nenhum serviço acessa o banco de outro, nem para leitura.** Tudo que
atravessa a fronteira é mensagem assíncrona ou chamada REST explícita.

Ler o banco alheio "só com um `SELECT`" já acopla os dois serviços pelo
schema. Uma coluna renomeada aqui quebraria o outro serviço em produção, sem
nenhum teste deste repositório perceber, e os dois deixariam de poder ser
implantados de forma independente. Por esse motivo, a Lambda de autenticação,
que hoje lê a tabela `clientes` direto, passa a chamar
`GET /clientes/por-cpf/{cpf}`.

### 3. Organização do código

A arquitetura hexagonal descrita no
[ADR-001](../adr/ADR-001-clean-architecture-hexagonal.md) continua valendo.
A dependência sempre aponta para o domínio. A tabela mostra onde entra o que
é novo na Fase 4:

| Pacote | Hoje | Novo na Fase 4 |
| --- | --- | --- |
| `domain` | Entidades, enums, *value objects*, exceções e portas `*Repository` | `CANCELADA` em `SituacaoOrdemServicoEnum`; o modelo da instância da Saga e sua porta de repositório |
| `application` | Um caso de uso por classe (`*UseCase.executar(...)`) | Orquestrador da Saga: um caso de uso por evento recebido, que avança ou compensa um passo |
| `adapters/in/web` | Controllers REST | `GET /servicos/{id}`, `GET /ordem-servicos/{id}/historico` |
| `adapters/in/messaging` | — | Consumidor da fila de respostas (sondagem longa, roteamento por `type`, inbox) |
| `adapters/out/persistence` | Entidades JPA e repositórios | Tabelas de inbox, outbox e instância da Saga |
| `adapters/out/messaging` | — | Envelope, serialização Jackson e publicadores SQS/SNS (outbox) |
| `adapters/out/historico` | — | Gravação das transições no DynamoDB `os-historico` |
| `config` | Segurança, OpenAPI, exceções, observabilidade | Clientes AWS (SQS, SNS, DynamoDB, SSM) |

Regra que vale para o novo código: **nenhum caso de uso conhece SQS, SNS ou
JSON.** Ele recebe e devolve objetos de aplicação, e os adapters fazem a
tradução para o envelope. É essa separação que permite testar a máquina de
passos da Saga sem LocalStack.

### 4. Convenções de nome

O código é escrito em português, e as convenções já existentes continuam
valendo:

| Elemento | Padrão | Exemplo |
| --- | --- | --- |
| Entidade de domínio | Substantivo no singular | `OrdemServico`, `Servico` |
| Porta de persistência (domínio) | `<Entidade>Repository` | `ServicoRepository` |
| Implementação da porta | `<Entidade>RepositoryImpl` | `ServicoRepositoryImpl` |
| Repositório Spring Data | `<Entidade>JpaRepository` | `ServicoJpaRepository` |
| Entidade JPA | `<Entidade>Entity` | `ServicoEntity` |
| Caso de uso | `<Verbo><Objeto>UseCase`, método `executar(...)` | `AbrirOrdemServicoUseCase` |
| Porta não persistente | `<Nome>Port` / `<Nome>Adapter` | `NotificacaoOrdemServicoPort`, `SmtpNotificacaoOrdemServicoAdapter` |
| Métodos de repositório | Verbo em português | `buscarPorId`, `salvar` |
| Identificadores | `UUID` | — |

Os nomes das mensagens (`AbrirDiagnostico`, `DiagnosticoConcluido`, …) e os
campos do envelope seguem o contrato da seção 5, em camelCase, e não são
traduzidos nem renomeados.

### 5. Contrato de mensagens

Eventos vão por **SNS** e comandos por **SQS**. O corpo é **JSON**
serializado com Jackson.

#### Envelope

Toda mensagem, em qualquer direção, usa o mesmo envelope. **Ele não deve ser
alterado**: o Execução já está implementado sobre ele.

```json
{
  "messageId": "uuid",
  "type": "AbrirDiagnostico",
  "version": 1,
  "sagaId": "uuid",
  "osId": "uuid",
  "correlationId": "string",
  "causationId": "uuid",
  "occurredAt": "2026-11-11T19:00:00Z",
  "payload": { }
}
```

| Campo | Significado |
| --- | --- |
| `messageId` | Identidade única desta mensagem. É a chave de deduplicação do consumidor. |
| `type` | Nome do comando ou evento. O consumidor roteia por ele. |
| `version` | Versão do contrato de `payload` para aquele `type`. Hoje é sempre `1`. |
| `sagaId` | Instância da Saga a que a mensagem pertence. |
| `osId` | Ordem de serviço afetada. Repete `payload.osId`. |
| `correlationId` | Igual em todas as mensagens de um mesmo fluxo, desde a requisição HTTP que o iniciou. Serve para juntar logs e traces de vários serviços. |
| `causationId` | `messageId` da mensagem que causou esta. Forma a cadeia de causa e efeito, mensagem a mensagem. |
| `occurredAt` | Instante (UTC, ISO-8601) em que o fato ocorreu ou o comando foi emitido. |
| `payload` | Conteúdo específico do `type`. |

O Execução **rejeita** a mensagem se `version != 1`, se `messageId`,
`sagaId`, `osId` ou `payload` forem nulos, ou se `payload.osId` for diferente
de `osId`.

#### Comandos OS → Execução

Fila `mecanica-execucao-comandos`. A URL fica no SSM em
`/mecanica/execucao/command-queue-url`.

| `type` | `payload` |
| --- | --- |
| `AbrirDiagnostico` | `{ "osId", "veiculoId", "placa" }` |
| `ReservarPecas` | `{ "osId", "pecas": [ { "pecaId", "qtd" } ] }`: lista não vazia, `qtd > 0`, sem `pecaId` repetido |
| `LiberarReservaPecas` | `{ "osId" }` |
| `IniciarExecucao` | `{ "osId" }` |

#### Eventos Execução → OS

Tópico `mecanica-execucao-eventos`, com ARN no SSM em
`/mecanica/execucao/event-topic-arn`. **O OS é dono da assinatura** entre esse
tópico e a sua fila `mecanica-os-respostas`, com entrega bruta
(`raw_message_delivery`), para que o corpo chegue como o próprio envelope.

| `type` | `payload` |
| --- | --- |
| `DiagnosticoAberto` | `{ "osId" }` |
| `DiagnosticoConcluido` | `{ "osId", "itens": [ { "servicoId", "precoMdo", "pecas": [ { "pecaId", "qtd", "valorUnit" } ] } ] }`: insumo do orçamento |
| `PecasReservadas` | `{ "osId" }` |
| `ReservaPecasRecusada` | `{ "osId", "faltantes": [ { "pecaId", "qtd" } ] }`: falha permanente, dispara compensação |
| `ReservaPecasLiberada` | `{ "osId" }` |
| `ExecucaoIniciada` | `{ "osId" }` |
| `ExecucaoFinalizada` | `{ "osId", "tempos": [ { "servicoId", "segundos" } ] }` |

#### OS ↔ Billing (a combinar)

Mesmo envelope. Proposta inicial:

- OS envia `GerarOrcamento` (com os itens de `DiagnosticoConcluido`),
  `CobrarPagamento`, `CancelarOrcamento` e `CancelarCobranca`.
- Billing responde `OrcamentoGerado`, `OrcamentoAprovado` /
  `OrcamentoRecusado` e `PagamentoConfirmado` / `PagamentoRecusado`.

#### Garantias

- **Entrega é pelo menos uma vez.** O Execução publica a partir de uma
  outbox e pode republicar o mesmo evento. Todo consumidor deste serviço
  deduplica por `messageId`, registrando-o numa inbox **na mesma transação**
  do efeito de negócio.
- **Mudança de estado e mensagem saem juntas.** A mensagem é gravada numa
  outbox na mesma transação que muda a OS ou a Saga, e só é publicada depois
  do commit. Nunca se publica antes de confirmar no banco.
- **Mensagem que falha não é apagada da fila.** O SQS a reentrega e, depois
  de 5 tentativas, ela vai para a fila morta.

### 6. A Saga

| # | Passo | O OS faz | Espera | Compensação se falhar |
| --- | --- | --- | --- | --- |
| 1 | Abrir OS | Grava a OS em `RECEBIDA`, cria a Saga, envia `AbrirDiagnostico` | `DiagnosticoAberto` | — |
| 2 | Diagnóstico | `EM_DIAGNOSTICO` | `DiagnosticoConcluido` | OS → `CANCELADA` |
| 3 | Orçamento | Envia `GerarOrcamento`, `AGUARDANDO_APROVACAO` | `OrcamentoAprovado` | `CancelarOrcamento`, OS → `EM_DIAGNOSTICO` |
| 4 | Reserva | Envia `ReservarPecas` | `PecasReservadas` | Em `ReservaPecasRecusada`: `CancelarOrcamento`, OS → `AGUARDANDO_APROVACAO` |
| 5 | Execução | Envia `IniciarExecucao`, `EM_EXECUCAO` | `ExecucaoFinalizada` | `LiberarReservaPecas` (Execução) e `CancelarOrcamento` (Billing) |
| 6 | Cobrança | `FINALIZADA`, envia `CobrarPagamento` | `PagamentoConfirmado` | `CancelarCobranca`, OS fica `FINALIZADA` |
| 7 | Entrega | `ENTREGUE`, encerra a Saga | — | — |

O passo 5 mostra por que a Saga existe: uma única falha desfaz efeitos em
**dois serviços diferentes**, e nenhuma transação de banco consegue fazer
isso.

## Contrato com os demais repositórios

| Repositório | O que este consome dele | O que ele consome deste |
| --- | --- | --- |
| Execução (`fiap-soat-mecanica-execucao`) | Eventos no tópico `mecanica-execucao-eventos`; URL da fila de comandos e ARN do tópico no SSM | Comandos na fila `mecanica-execucao-comandos`; `GET /servicos/{id}` |
| Billing (`fiap-soat-mecanica-billing`) | Eventos de orçamento e pagamento (a combinar) | Comandos de orçamento e cobrança (a combinar) |
| Auth (`fiap-soat-mecanica-auth`) | Secret JWT e contrato de claims (inalterado, ver RFC-001) | `GET /clientes/por-cpf/{cpf}`, em vez da leitura direta da tabela `clientes` |
| Kubernetes (`fiap-soat-mecanica-k8s`) | Cluster, Traefik, OpenTelemetry Operator (inalterado) | — |

As localizações dos recursos deste serviço (fila de respostas, tópico de
eventos) são publicadas no SSM sob `/mecanica/os/...`, no mesmo padrão do
Execução, e criadas por Terraform neste repositório.

## Alternativas avaliadas

### Coordenação da Saga

| Opção | Prós | Contras | Decisão |
| --- | --- | --- | --- |
| **Orquestrada, com o orquestrador no OS** | O fluxo inteiro e o estado de cada instância ficam num só lugar, visível numa tabela; compensações explícitas; Execução e Billing não se conhecem | O OS concentra a lógica do fluxo e vira dependência de todos | **Escolhido** |
| Coreografada (cada serviço reage aos eventos dos outros) | Sem ponto central; serviços mais autônomos | O fluxo fica espalhado em três repositórios; difícil saber em que passo uma OS está; compensações em ciclo são difíceis de raciocinar | Rejeitado |

### Transporte

| Opção | Decisão | Motivo |
| --- | --- | --- |
| **SNS (eventos) + SQS (comandos)** | **Escolhido** | Gerenciados, sem servidor para operar no Learner Lab; fila morta e reentrega nativas; já usados pelo Execução |
| Kafka (MSK) | Rejeitado | Custo e operação desproporcionais para o volume e o crédito disponível |
| REST síncrono entre serviços | Rejeitado | Acopla a disponibilidade dos serviços: se o Billing cair, a OS para no meio do fluxo sem retentativa |

### Serialização

| Opção | Decisão | Motivo |
| --- | --- | --- |
| **JSON (Jackson)** | **Escolhido** | Legível na fila e no log; o Execução já está escrito em JSON |
| Avro / Protobuf | Rejeitado | Sem registro de schema, o ganho do formato binário não compensa o custo |

## Riscos e mitigações

| Risco | Mitigação |
| --- | --- |
| Evento duplicado aplica o mesmo efeito duas vezes | Inbox por `messageId` na mesma transação do efeito |
| Falha entre gravar no banco e publicar perde a mensagem | Outbox transacional com publicador agendado |
| Mensagem envenenada trava a fila | Redrive para fila morta após 5 tentativas |
| Contrato com o Billing ainda indefinido atrasa os passos 3 e 6 | Passos do Execução implementados primeiro; os do Billing entram atrás do mesmo envelope assim que o contrato for fechado |
| Eventos fora de ordem (ex.: `ExecucaoIniciada` depois de `ExecucaoFinalizada`) | O orquestrador valida o evento contra o passo atual da Saga e ignora transições inválidas, registrando no log |

## Questões em aberto

- **Quem dispara o orçamento.** O README do Billing registra que uma proposta
  de 11/11 faz o Billing consumir `DiagnosticoConcluido` direto do Execução,
  o que é coreografia e conflita com o orquestrador desta RFC. Precisa ser
  decidido com o grupo do Billing antes do passo 3. Há um argumento a favor
  do orquestrador: o `Orcamento` do Billing exige `clienteId`, que não vem em
  `DiagnosticoConcluido` e só o OS conhece.
- Contrato final das mensagens do Billing (nomes, `payload`, filas e tópicos).
  O domínio do Billing tem o estado `EXPIRADO`, que a tabela da Saga não
  prevê: falta definir o evento (`OrcamentoExpirado`?) e a reação do OS.
- O Execução declara duas limitações próprias (RFC-F4-02 daquele
  repositório): `IniciarExecucao` confirma o estoque e inicia a ordem em
  operações separadas, e uma nova reserva para a mesma OS depois de
  `LiberarReservaPecas` não é garantida. O OS não deve reenviar
  `ReservarPecas` para uma OS cuja reserva foi liberada sem antes combinar
  isso com o Execução.
- Os eventos que o Execução emite a partir de ações REST
  (`DiagnosticoConcluido`, `ExecucaoIniciada`, `ExecucaoFinalizada`) chegam
  com `causationId` nulo e com o `correlationId` do cabeçalho
  `X-Correlation-Id` da requisição, que pode estar ausente. O OS precisa
  aceitar esses campos nulos e encontrar a Saga pelo `sagaId`.
- Como a Lambda de autenticação se autentica em
  `GET /clientes/por-cpf/{cpf}` (hoje o endpoint exige `ATENDENTE`) e quais
  campos ela realmente precisa.
- `GET /servicos/{id}` ainda não existe, e o Execução já o chama **sem
  token**, lendo os campos `nome` e `statusRecursoEnum` (aceita só `ATIVO`)
  da resposta. É preciso decidir entre liberar a rota sem autenticação ou
  pedir ao Execução que envie credencial.
- Como `correlationId` se relaciona com o `X-Request-Id` do
  [ADR-010](../adr/ADR-010-logs-estruturados-request-id.md) e com o trace do
  OpenTelemetry na propagação entre serviços.
- Os pacotes de `application` não são uniformes (alguns domínios usam o
  subpacote `usecase`, outros não). O código novo usa
  `application/<dominio>/usecase`.
