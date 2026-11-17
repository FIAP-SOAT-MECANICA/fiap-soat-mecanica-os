# FIAP SOAT - Mecanica do Braia

API REST desenvolvida para gerenciamento de uma oficina mecanica, como parte do
**Tech Challenge**. Na Fase 4 este repositorio e o **OS Service**, orquestrador da saga
entre os microsservicos de Execucao e Billing.

## Problema

O sistema foi idealizado para resolver dores comuns de oficinas mecanicas:

- Erros na priorizacao dos atendimentos
- Falhas no controle de pecas e insumos
- Dificuldade em acompanhar o status dos servicos
- Perda de historico de clientes e veiculos
- Ineficiencia no fluxo de orcamentos e ordens de servico

## Tecnologias

- Java 21
- Spring Boot 3.5
- Spring Web
- Spring Data JPA
- Spring Mail
- Spring Security
- JWT
- PostgreSQL 17
- Flyway
- Maven
- Docker e Docker Compose
- MailHog
- Kubernetes
- Spring Boot Actuator
- Swagger/OpenAPI
- JUnit, Mockito, Testcontainers e MockMvc
- JaCoCo
- SonarQube
- ZAP by Checkmarx
- GitHub Actions

## Arquitetura

O projeto segue uma organizacao inspirada em Clean Architecture e Arquitetura Hexagonal.

```text
src/main/java/br/com/fiap/soat/mecanica
|-- domain                  -> regras de negocio, entidades, enums, value objects e portas
|-- application             -> casos de uso e orquestrador da saga (application/saga)
|-- adapters
|   |-- in/web              -> controllers, DTOs, mappers e filtro de seguranca
|   |-- in/messaging        -> consumidor SQS da fila de respostas (long polling)
|   `-- out
|       |-- persistence     -> JPA (cadastros) e JDBC (saga, inbox, outbox)
|       |-- messaging       -> envelope, publicador SQS e publicador da outbox
|       |-- historico       -> historico da OS no DynamoDB
|       |-- notification    -> adapter SMTP para notificacoes por e-mail
|       `-- security        -> servicos de JWT e criptografia de senha
`-- config                  -> seguranca, OpenAPI e tratamento global de excecoes
```

## Principais dominios

Na Fase 4 o monolito foi decomposto em tres microsservicos (ver
[RFC-002](docs/rfc/RFC-002-decomposicao-os-service.md)). Este repositorio e o **OS Service**:

- **Usuario:** usuarios autenticados da API.
- **Cliente:** pessoa fisica ou juridica atendida pela oficina.
- **Veiculo:** veiculo associado a um cliente.
- **Servico:** catalogo de servicos (o Execucao consulta `GET /servicos/{id}`).
- **Ordem de Servico:** entidade central; o ciclo de vida e conduzido pela saga.
- **Saga da OS:** instancia do orquestrador, uma por OS (`saga_ordem_servico`).

Sairam deste repositorio: pecas, prestacao de servico e alocacao de pecas (servico
**Execucao**, DynamoDB) e orcamento e pagamento (servico **Billing**, PostgreSQL).
Nenhum servico acessa o banco de outro.

| Servico | Repositorio | Banco | Papel |
| --- | --- | --- | --- |
| OS (este) | `fiap-soat-mecanica-os` | PostgreSQL (RDS) + DynamoDB (historico) | Orquestrador da saga |
| Execucao | `fiap-soat-mecanica-execucao` | DynamoDB | Diagnostico, estoque, execucao |
| Billing | `fiap-soat-mecanica-billing` | PostgreSQL | Orcamento e cobranca |

## Saga orquestrada

O OS e o unico servico que fala com os outros dois. Ele manda **comandos** para a fila SQS
de cada servico e recebe os **eventos** deles na fila `mecanica-os-respostas`, que assina os
topicos SNS `mecanica-execucao-eventos` e `mecanica-billing-eventos`. Envelope comum (JSON):

```json
{ "messageId": "uuid", "type": "AbrirDiagnostico", "version": 1, "sagaId": "uuid", "osId": "uuid",
  "correlationId": "string", "causationId": "uuid", "occurredAt": "2026-11-11T19:00:00Z", "payload": { } }
```

```mermaid
sequenceDiagram
    participant M as Mecanico
    participant OS as OS Service
    participant EX as Execucao
    participant BI as Billing
    M->>OS: POST /ordem-servicos (RECEBIDA)
    OS->>EX: AbrirDiagnostico
    EX-->>OS: DiagnosticoAberto (EM_DIAGNOSTICO)
    EX-->>OS: DiagnosticoConcluido {itens}
    OS->>BI: GerarOrcamento (AGUARDANDO_APROVACAO)
    BI-->>OS: OrcamentoAprovado
    OS->>EX: ReservarPecas {cycleId, pecas}
    EX-->>OS: PecasReservadas
    OS->>EX: IniciarExecucao (EM_EXECUCAO)
    EX-->>OS: ExecucaoFinalizada (FINALIZADA)
    OS->>BI: SolicitarPagamento
    BI-->>OS: PagamentoConfirmado (ENTREGUE, saga CONCLUIDA)
```

| # | Passo | OS faz | Espera | Compensacao |
| --- | --- | --- | --- | --- |
| 1 | Abertura | grava `RECEBIDA`, cria a saga, envia `AbrirDiagnostico` | `DiagnosticoAberto` | — |
| 2 | Diagnostico | `EM_DIAGNOSTICO` | `DiagnosticoConcluido` | OS `CANCELADA` |
| 3 | Orcamento | envia `GerarOrcamento`, `AGUARDANDO_APROVACAO` | `OrcamentoAprovado` | `OrcamentoRecusado`: OS `CANCELADA` |
| 4 | Reserva | envia `ReservarPecas` | `PecasReservadas` | `ReservaPecasRecusada`: `CancelarOrcamento`, OS fica `AGUARDANDO_APROVACAO` |
| 5 | Execucao | envia `IniciarExecucao`, `EM_EXECUCAO` | `ExecucaoFinalizada` | desistencia: `LiberarReservaPecas` (Execucao) + `CancelarOrcamento` (Billing) |
| 6 | Cobranca | `FINALIZADA`, envia `SolicitarPagamento` | `PagamentoConfirmado` | `PagamentoRecusado`: `CancelarCobranca`, OS fica `FINALIZADA` |
| 7 | Entrega | `ENTREGUE`, saga `CONCLUIDA` | — | — |

A desistencia do cliente (`PATCH /ordem-servicos/{id}/cancelar`) compensa os passos ja feitos
**em ordem inversa** e registra cada compensacao na saga. No passo 5 isso gera compensacao em
dois servicos diferentes, o caso que uma transacao de banco nao resolve.

### Garantias de entrega

| Regra | Como |
| --- | --- |
| Mudanca de estado e mensagem na mesma transacao | **Outbox** (`outbox`): o orquestrador so grava; o `PublicadorDaOutbox` publica depois do commit, com `FOR UPDATE SKIP LOCKED` |
| Entrega pelo menos uma vez, efeito uma vez | **Inbox** (`mensagem_processada`): `INSERT ... ON CONFLICT DO NOTHING` na mesma transacao do efeito |
| Mensagem que falha nao e apagada | o consumidor so apaga depois do commit; 5 falhas levam a DLQ |
| Eventos fora de ordem | evento de um passo a frente volta para a fila (`EventoForaDeOrdemException`); atrasado ou repetido e ignorado com log |
| Concorrencia entre replicas | a saga e lida com `SELECT ... FOR UPDATE` |
| Rastreamento | o `traceparent` W3C e gravado na outbox, vai como atributo SQS e e reativado no consumidor |

### Acompanhar uma OS

```http
GET /ordem-servicos/{id}/saga        -> passo atual, estado, passos concluidos e compensados
GET /ordem-servicos/{id}/historico   -> transicoes de situacao em ordem cronologica (DynamoDB os-historico)
```

As duas rotas exigem perfil `MECANICO`. Para o video, a tabela da saga tambem pode ser vista
direto no banco:

```sql
SELECT os_id, passo_atual, estado, passos_concluidos, passos_compensados, motivo, atualizado_em
FROM saga_ordem_servico ORDER BY atualizado_em DESC;
```

### Configuracao da mensageria

| Variavel | Uso |
| --- | --- |
| `EXECUCAO_COMMAND_QUEUE_URL` | fila de comandos do Execucao (SSM `/mecanica/execucao/command-queue-url`) |
| `BILLING_COMMAND_QUEUE_URL` | fila de comandos do Billing (SSM `/mecanica/billing/command-queue-url`) |
| `OS_RESPONSE_QUEUE_URL` | fila de respostas do OS (SSM `/mecanica/os/response-queue-url`) |
| `HISTORICO_TABLE_NAME` | tabela DynamoDB do historico (padrao `os-historico`) |
| `MESSAGING_CONSUMER_ENABLED` | liga o consumidor SQS (padrao `false`) |
| `MESSAGING_OUTBOX_ENABLED` | liga o publicador da outbox (padrao `false`; desligado, as mensagens ficam pendentes na tabela) |

A infraestrutura (SQS, DLQ, SNS, assinaturas, SSM, DynamoDB, RDS) esta em Terraform em `infra/`
e e aplicada pelo job `infra` do `cd.yml`. Ordem de deploy em
[`infra/messaging/README.md`](infra/messaging/README.md).

## Listagem operacional de ordens de servico

O endpoint abaixo apresenta a fila ativa de trabalho da oficina:

```http
GET /ordem-servicos?page=0&size=20
Authorization: Bearer <token-do-mecanico>
```

O acesso e restrito ao perfil `MECANICO`. Os parametros sao:

| Parametro | Padrao | Restricao |
| --- | ---: | --- |
| `page` | `0` | zero ou maior |
| `size` | `20` | entre `1` e `100` |

Valores invalidos retornam `400 Bad Request`. Uma pagina sem registros retorna
`200 OK` com `content` vazio.

A consulta e executada e paginada no PostgreSQL. Ela retorna somente OS com
`status = ATIVO` nas situacoes abaixo, nesta ordem de prioridade:

1. `EM_EXECUCAO`
2. `AGUARDANDO_APROVACAO`
3. `EM_DIAGNOSTICO`
4. `RECEBIDA`

Dentro da mesma situacao, as OS sao ordenadas por `dataRecebida ASC` e, em caso
de empate, por `id ASC`. Ordens `FINALIZADA`, `ENTREGUE` ou com status `INATIVO`
nao aparecem na fila, mas continuam persistidas e acessiveis pelos demais
fluxos. Nao ha exclusao fisica.

Exemplo de resposta:

```json
{
  "content": [
    {
      "id": "66666666-6666-6666-6666-666666666666",
      "status": "ATIVO",
      "situacao": "EM_EXECUCAO",
      "dataRecebida": "2026-07-13T10:00:00",
      "dataDiagnostico": "2026-07-13T10:30:00",
      "dataAguardandoAprovacao": "2026-07-13T11:00:00",
      "dataExecucao": "2026-07-13T11:30:00",
      "dataFinalizada": null,
      "dataEntregue": null,
      "pago": false,
      "valorTotal": 150.00,
      "observacao": "Revisao preventiva",
      "veiculoId": "44444444-4444-4444-4444-444444444444",
      "usuarioId": "22222222-2222-2222-2222-222222222222"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

`status` representa se o registro esta ativo ou inativo. `situacao` representa
a etapa operacional da OS. O endpoint publico existente
`GET /ordem-servicos/veiculo/placa/{placa}` foi preservado sem alteracoes de
contrato ou seguranca.

## Notificacoes de status por e-mail

As notificacoes usam uma porta de saida da camada de aplicacao e um adapter SMTP
baseado em Spring Mail. Os casos de uso existentes continuam responsaveis pelas
regras de transicao. Depois da persistencia, a aplicacao resolve o destinatario
pelo caminho OS -> veiculo -> cliente e agenda o envio para depois do commit da
transacao. O dominio nao conhece SMTP nem `JavaMailSender`.

Uma mensagem de texto simples e enviada nas transicoes:

- `RECEBIDA` -> `EM_DIAGNOSTICO`
- `EM_DIAGNOSTICO` -> `AGUARDANDO_APROVACAO`
- `AGUARDANDO_APROVACAO` -> `EM_DIAGNOSTICO`
- `AGUARDANDO_APROVACAO` -> `EM_EXECUCAO`
- `EM_EXECUCAO` -> `FINALIZADA`
- `FINALIZADA` -> `ENTREGUE`

Nao ha envio na criacao inicial da OS, no cancelamento, quando a situacao nao
muda, quando a transicao/persistencia falha ou quando somente parte das
prestacoes e finalizada.

O MailHog e usado somente em desenvolvimento e validacoes locais. Ele captura
as mensagens e nao as entrega a caixas de e-mail reais. A interface fica em
`http://localhost:8025` e o SMTP em `localhost:1025`.

O assunto segue o formato `Atualização da ordem de serviço {id}` e o corpo
informa o nome do cliente, o identificador da OS e as situações anterior e nova
com descrições amigáveis (`Recebida`, `Em diagnóstico`, `Aguardando aprovação`,
`Em execução`, `Finalizada` e `Entregue`).

Configuracoes externalizadas:

| Variavel | Padrao local | Descricao |
| --- | --- | --- |
| `EMAIL_NOTIFICATIONS_ENABLED` | `true` | habilita/desabilita notificacoes |
| `EMAIL_FROM` | `no-reply@mecanica.local` | remetente das mensagens |
| `SPRING_MAIL_HOST` | `localhost` | host SMTP; no Compose usa `mailhog` |
| `SPRING_MAIL_PORT` | `1025` | porta SMTP |
| `SPRING_MAIL_USERNAME` | vazio | usuario SMTP, se exigido fora do MailHog |
| `SPRING_MAIL_PASSWORD` | vazio | senha SMTP, sempre fornecida externamente |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `false` | autenticacao SMTP |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `false` | STARTTLS |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT` | `5000` | timeout de conexao em ms |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT` | `5000` | timeout de leitura em ms |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_WRITETIMEOUT` | `5000` | timeout de escrita em ms |

Nenhuma credencial real deve ser versionada. No perfil de testes, as
notificacoes ficam desabilitadas e `JavaMailSender` e mockado nos testes do
adapter, portanto a suite nao abre conexao SMTP.

Falhas de resolucao do destinatario ou de envio sao registradas com o ID da OS e
o tipo da excecao, sem credenciais ou dados sensiveis. Elas nao revertem a
atualizacao nem alteram a resposta HTTP de sucesso. Esta versao nao implementa
retry automatico, fila, Outbox Pattern ou envio assincrono.

## Perfis de acesso

A API utiliza JWT e autorizacao por cargo.

| Cargo | Responsabilidades principais |
| --- | --- |
| `ATENDENTE` | Cadastro e consulta de clientes e veiculos |
| `MECANICO` | Cadastro de servicos, abertura e cancelamento de OS, consulta da saga e do historico |
| `ALMOXARIFE` | Sem rotas neste servico (estoque saiu para o Execucao) |

Servicos internos se autenticam por chave de API (`X-Api-Key`), uma por consumidor, com escopo
proprio: a Lambda de autenticacao le `GET /clientes/por-cpf/{cpf}/situacao` e o Execucao le
`GET /servicos/{id}`.

Rotas publicas:

- `POST /usuarios`
- `POST /auth/login`
- `GET /ordem-servicos/veiculo/placa/{placa}`
- Swagger/OpenAPI

As demais rotas exigem token JWT.

## Pre-requisitos

Antes de rodar o projeto, instale:

- Java 21
- Maven 3.9+ ou use o Maven Wrapper do projeto
- Docker Desktop com o daemon ativo
- Docker Compose
- Terraform >= 1.14.5 e < 1.16.0 para o ambiente Kind
- `kubectl` para observabilidade do cluster

## Como rodar

Existem duas formas de executar o projeto. Use apenas uma delas por vez:

- **Aplicacao local + banco no Docker:** suba somente o PostgreSQL pelo Docker e
  rode a API pela IDE ou pelo Maven.
- **Tudo via Docker Compose:** suba a API e os demais servicos pelo Docker.

> Atencao: o comando `docker-compose up -d` tambem sobe a API no container
> `mecanica-api` usando a porta `8080`. Se a API ja estiver rodando pelo Docker,
> nao execute a aplicacao tambem pela IDE/Maven na mesma porta, pois ocorrera o
> erro `Port 8080 was already in use`.

Antes de usar o Docker Compose, crie o arquivo local de variaveis e troque
todos os placeholders. Esse arquivo e ignorado pelo Git:

```bash
cp .env.example .env
```

### 1. Subir somente o banco PostgreSQL

Use este modo quando quiser rodar a aplicacao localmente pela IDE ou pelo Maven.

```bash
docker-compose up -d postgres
```

O PostgreSQL fica disponivel em:

```text
localhost:5433
```

Configuracao local:

```text
database: mecanica
username: postgres
password: definida em DB_PASSWORD no arquivo .env local
```

### 2. Rodar a aplicacao localmente

Ao executar pela IDE ou Maven, forneca `SPRING_DATASOURCE_PASSWORD` e
`JWT_SECRET` no ambiente do processo. O segredo JWT deve estar em Base64 e
nao deve ser versionado.

No Windows:

```bash
./mvnw.cmd spring-boot:run
```

No Linux/macOS:

```bash
./mvnw spring-boot:run
```

A API ficara disponivel em:

```text
http://localhost:8080
```

### 3. Subir tudo via Docker Compose

Este comando constroi e sobe a API, o PostgreSQL, o MailHog, o SonarQube e o
banco do SonarQube:

```bash
docker compose up --build -d
```

Servicos principais:

```text
API:        http://localhost:8080
PostgreSQL: localhost:5433
MailHog UI: http://localhost:8025
MailHog SMTP: localhost:1025
SonarQube:  http://localhost:9000
```

Neste modo, a API ja fica disponivel em `http://localhost:8080` pelo container
`mecanica-api`. Nao e necessario iniciar a aplicacao pela IDE/Maven.

Para rodar a API localmente com PostgreSQL e MailHog no Docker:

```bash
docker compose up -d postgres mailhog
./mvnw.cmd spring-boot:run
```

### Como validar manualmente

1. Execute `docker compose up --build -d` e aguarde a API e o PostgreSQL.
2. Autentique em `POST /auth/login` com um usuario `MECANICO`.
3. Chame `POST /ordem-servicos` com `veiculoId` para abrir uma OS em `RECEBIDA`.
4. Chame `GET /ordem-servicos/{id}/saga` e confirme o passo `ABERTURA`; na tabela `outbox` ha um
   `AbrirDiagnostico` pendente (o publicador fica desligado sem AWS).
5. Para rodar a saga inteira sem AWS, use os cenarios BDD (`./mvnw.cmd test -Dtest=SagaBddTest`).
6. Chame `PATCH /ordem-servicos/{id}/cancelar` e confirme a saga `CANCELADA`.
7. Acesse `http://localhost:8025` e confirme o e-mail de mudanca de situacao.
8. Chame `GET /ordem-servicos?page=0&size=20` com o token do mecanico e confirme o filtro e a ordenacao por situacao.
9. Consulte `GET /ordem-servicos/veiculo/placa/{placa}` para validar que o fluxo publico por placa continua disponivel.
10. Ao terminar, execute `docker compose down`.

### 4. Subir somente o SonarQube

Use este modo quando a aplicacao ja estiver rodando localmente pela IDE/Maven e
voce quiser subir apenas o SonarQube e o banco dele:

```bash
docker-compose up -d sonarqube
```

O SonarQube fica disponivel em:

```text
http://localhost:9000
```

Na primeira execucao, aguarde alguns instantes ate o servico finalizar a
inicializacao e altere imediatamente as credenciais iniciais solicitadas pela
interface.

Para verificar o status do SonarQube:

```text
http://localhost:9000/api/system/status
```

Quando o retorno indicar `status: UP`, a interface ja pode ser acessada.

## Infraestrutura local com Terraform e Kind

O Terraform e o unico responsavel por criar o cluster Kind e aplicar os YAMLs
de `k8s/`. O ambiente inclui o namespace `mecanica`, PostgreSQL com PVC, API,
metrics-server e HPA. O NodePort `30080` e encaminhado pelo Kind para
`http://localhost:8080`.

```mermaid
flowchart LR
  CI[CI Maven] --> GHCR[GHCR: imagem por commit SHA]
  GHCR --> TF[Terraform]
  TF --> KIND[Cluster Kind]
  KIND --> DB[PostgreSQL e PVC]
  KIND --> API[API]
  KIND --> METRICS[metrics-server]
  METRICS --> HPA[HPA CPU e memoria]
  HPA --> API
  DB --> API
```

Crie as variaveis locais a partir do exemplo e substitua somente os
placeholders. `terraform.tfvars`, state e kubeconfig sao ignorados pelo Git:

```bash
cd infra/k8s
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform fmt -check -recursive
terraform validate
terraform plan -out=local.tfplan
terraform apply local.tfplan
```

`api_image` deve apontar para uma imagem publica e imutavel no formato
`ghcr.io/<owner>/<repo>:<commit_sha>`. Os valores `db_password` e `jwt_secret`
sao sensiveis; o Terraform gera os objetos `Secret` sem manter valores reais
nos YAMLs. Como esses valores existem no state local, proteja o arquivo de
state e nunca o versione.

Depois do `apply`, use `kubectl` apenas para observar e validar:

```bash
export KUBECONFIG="$PWD/kubeconfig"
kubectl get all,pvc -n mecanica
kubectl rollout status deployment/mecanica-db -n mecanica
kubectl rollout status deployment/mecanica-api -n mecanica
kubectl top pods -n mecanica
kubectl describe hpa/mecanica-api-hpa -n mecanica
curl http://localhost:8080/actuator/health/readiness
```

Finalize o ambiente explicitamente:

```bash
terraform destroy
```

O destroy remove o cluster e o PVC interno; portanto, os dados do PostgreSQL
sao perdidos. Localmente, o cluster pode ficar ativo ate esse comando. No
GitHub Actions ele e efemero e o `destroy` roda sempre ao final. Consulte o
guia detalhado em [`infra/k8s/README.md`](infra/k8s/README.md).

## Swagger

Com a aplicacao rodando, acesse:

```text
http://localhost:8080/swagger-ui.html
```

O Swagger esta configurado com autenticacao Bearer JWT.

## Autenticacao

### Criar usuario

```http
POST /usuarios
Content-Type: application/json
```

Exemplo:

```json
{
  "nome": "Mecanico Teste",
  "email": "mecanico@email.com",
  "senha": "Senha@123",
  "cargoEnum": "MECANICO"
}
```

### Fazer login

```http
POST /auth/login
Content-Type: application/json
```

Exemplo:

```json
{
  "email": "mecanico@email.com",
  "senha": "Senha@123"
}
```

### Usar o token

Envie o token retornado no header:

```http
Authorization: Bearer SEU_TOKEN
```

## Usuarios mockados

A migration `V9__mock_dados.sql` cria usuarios para facilitar testes manuais.

| Cargo | Email | Senha |
| --- | --- | --- |
| `ATENDENTE` | `atendente@mecanica.test` | `Senha@123` |
| `MECANICO` | `mecanico@mecanica.test` | `Senha@123` |
| `ALMOXARIFE` | `almoxarife@mecanica.test` | `Senha@123` |

## Banco de dados

O banco e versionado com Flyway em:

```text
src/main/resources/db/migration
```

Principais tabelas:

- `usuarios`
- `clientes`
- `veiculos`
- `servicos`
- `ordem_servicos`
- `saga_ordem_servico` (instancia da saga, `V13`)
- `outbox` (mensagens a publicar, `V12`)
- `mensagem_processada` (inbox de idempotencia, `V11`)

`pecas`, `prestacao_servicos` e `alocacao_pecas` foram removidas na `V10` (passaram para o
Execucao). O historico de transicoes fica fora do PostgreSQL, na tabela DynamoDB `os-historico`.

## Melhorias de Infraestrutura e Testes

- **Dockerfile multi-stage:** a imagem agora compila a aplicação em um stage com Maven e JDK e copia apenas o `.jar` gerado para uma imagem runtime menor com JRE. O build Docker e autocontido e não depende de um artefato gerado localmente.
- **Usuario nao-root:** o stage final cria usuário e grupo dedicados para a aplicação, copia o artefato com permissão adequada e executa o processo Java com `USER mecanica`.
- **Healthcheck do banco:** o PostgreSQL no Docker Compose usa `pg_isready` para indicar quando o banco está pronto para conexões.
- **Ordem de subida no Compose:** a aplicação depende do PostgreSQL com `condition: service_healthy`, evitando tentativas de conexão antes do banco estar saudável.
- **Testcontainers nos testes de integracao:** os testes JPA e de contexto sobem PostgreSQL real com Testcontainers e injetam dinâmicamente `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` e `spring.datasource.driver-class-name`.
- **Banco real nos testes:** Flyway, Hibernate/JPA e repositories são validados contra PostgreSQL, mantendo os testes unitários independentes de container.

## Testes

Rodar testes:

```bash
./mvnw.cmd test
```

No Linux/macOS:

```bash
./mvnw test
```

> Os testes de integração usam Testcontainers. Mantenha o Docker em execução antes de rodar a suíte completa.

Rodar verificação completa com JaCoCo:

```bash
./mvnw.cmd clean verify
```

O projeto possui testes para:

- Entidades de dominio
- Value Objects
- Casos de uso
- Controllers
- Mappers
- Repositories
- Seguranca/JWT e chave de API
- Tratamento global de excecoes
- Mensageria: envelope, publicador e consumidor SQS e historico DynamoDB (LocalStack)
- Inbox, outbox e saga contra PostgreSQL real (Testcontainers)
- Propagacao de `traceparent` nas mensagens
- **BDD (Cucumber)** da saga em `src/test/resources/features/saga-ordem-servico.feature`: caminho
  feliz, evento duplicado, evento fora de ordem e as compensacoes (sem estoque, desistencia durante a
  execucao, pagamento recusado, orcamento recusado). Relatorio em `target/cucumber-reports/cucumber.html`

O relatorio de cobertura fica em:

```text
target/site/jacoco/index.html
```

Atualmente, o projeto exige cobertura minima de **80%** no `mvn verify`.

Evidencia (2026-11-17, `./mvnw clean verify`): **315 testes, 0 falhas, 94,3% de linhas cobertas**,
regra do JaCoCo atendida. A CI publica o relatorio JaCoCo (`jacoco-report`) e o do Cucumber
(`cucumber-report`) como artefatos de cada execucao.

## Smoke test da API

Existe um script de smoke test em:

```text
tests/api-smoke/api-smoke.mjs
```

Com a aplicacao rodando:

```bash
node .\tests\api-smoke\api-smoke.mjs
```

Tambem e possivel apontar para outra URL:

```bash
API_BASE_URL=http://localhost:8080 node tests/api-smoke/api-smoke.mjs
```

## Analise de seguranca com ZAP by Checkmarx

O projeto pode ser analisado com o ZAP by Checkmarx instalado localmente para
verificacao dinamica de vulnerabilidades na API em execucao.

Com a aplicacao rodando, configure o alvo no aplicativo:

```text
http://localhost:8080
```

Porém para que seja possível acessar Endpoints autorizados, é nesserario acessar com um token JWT no header.
A maioria dos Endpoint possui um PreAuthorize especificando qual Cargo tem acesso.

## CI/CD

O projeto possui pipelines no GitHub Actions em:

```text
.github/workflows/ci.yml
.github/workflows/cd.yml
```

### Integracao Continua (CI)

Executada a cada push nas branches `main`, `feat/**` e `feature/**`, e em pull
requests para `main` e `develop`. A pipeline executa:

- Checkout do repositorio
- Setup do JDK 21
- `./mvnw clean verify` (build, testes, cenarios BDD e verificacao de cobertura JaCoCo minima de 80%)
- Publicacao dos relatorios JaCoCo e Cucumber
- Analise SonarQube quando `SONAR_TOKEN` e `SONAR_HOST_URL` estiverem configurados

No deploy para o EKS, o job `infra` aplica o Terraform de `infra/rds` (se `RDS_VPC_ID` estiver
definido), `infra/messaging` e `infra/historico` com state no bucket `mecanica-tfstate-<account-id>`,
e repassa ao `ConfigMap` as URLs das filas e o nome da tabela, ligando o consumidor e o publicador
da outbox. Variaveis de repositorio: `RDS_VPC_ID`, `RDS_SUBNET_IDS`, `RDS_ALLOWED_SG_ID`,
`ASSINAR_BILLING`. Secrets opcionais: `API_KEY_AUTH_LAMBDA`, `API_KEY_EXECUCAO`.

Depois de uma CI bem-sucedida em `main`, o CD usa exatamente o
`workflow_run.head_sha`: publica `ghcr.io/<owner>/<repo>:<sha>`, executa
`terraform fmt/init/validate/plan/apply`, valida banco, API, metrics-server,
HPA e smoke test, e finalmente executa `terraform destroy` com `always()`.

Configure os GitHub Secrets `DB_PASSWORD` e `JWT_SECRET`. O pacote Container
no GHCR deve ter visibilidade publica; isso e verificado por um pull anonimo
antes do provisionamento. O token JWT deve ser fornecido em Base64.

## Documentacao

As decisoes deste repositorio estao em [`docs/`](docs/README.md):

- [RFC-001](docs/rfc/RFC-001-api-eks-learner-lab.md) — implantacao da API no EKS do AWS Academy Learner Lab: restricoes, contrato com os demais repositorios, alternativas, custo, validacao e riscos
- [ADR-001](docs/adr/ADR-001-clean-architecture-hexagonal.md) — Clean Architecture com portas e adaptadores
- [ADR-002](docs/adr/ADR-002-flyway-dono-do-schema.md) — Flyway como unico dono do schema; Hibernate em `validate`
- [ADR-003](docs/adr/ADR-003-jwt-dois-emissores.md) — JWT com dois emissores: usuarios internos e clientes da Auth serverless
- [ADR-004](docs/adr/ADR-004-notificacao-email-pos-commit.md) — notificacao por e-mail via porta de saida, apos o commit, sem retry
- [ADR-005](docs/adr/ADR-005-imagem-imutavel-ghcr.md) — imagem multi-stage nao-root no GHCR com tag igual ao SHA testado
- [ADR-006](docs/adr/ADR-006-kind-terraform-ambiente-efemero.md) — Kind + Terraform como ambiente efemero de validacao
- [ADR-007](docs/adr/ADR-007-deploy-eks-kubectl-secrets-manager.md) — deploy no EKS por `kubectl apply` com segredos do Secrets Manager
- [ADR-008](docs/adr/ADR-008-ingress-traefik.md) — `Ingress` para o Traefik declarado neste repositorio
- [ADR-009](docs/adr/ADR-009-otel-collector-instrumentation-datadog.md) — OpenTelemetry Collector e Instrumentation exportando para o Datadog
- [ADR-010](docs/adr/ADR-010-logs-estruturados-request-id.md) — logs estruturados em JSON com `requestId` e eventos de negocio
- [ADR-011](docs/adr/ADR-011-qualidade-cobertura-sonar-zap.md) — portoes de qualidade: JaCoCo 80%, Testcontainers, SonarQube, ZAP e smoke test
- [RFC-002](docs/rfc/RFC-002-decomposicao-os-service.md) — decomposicao em OS, Execucao e Billing
- [ADR-012](docs/adr/ADR-012-saga-orquestrada.md) — saga orquestrada no OS, e nao coreografada
- [ADR-013](docs/adr/ADR-013-sns-sqs-e-nao-kafka.md) — SNS para eventos e SQS para comandos, e nao Kafka
- [ADR-014](docs/adr/ADR-014-json-e-nao-avro.md) — JSON com envelope versionado, e nao Avro
- [ADR-015](docs/adr/ADR-015-historico-em-dynamodb.md) — historico da OS em DynamoDB alimentado pela outbox
- [ADR-016](docs/adr/ADR-016-inbox-outbox-transacionais.md) — inbox e outbox transacionais no PostgreSQL

A documentacao de dominio (linguagem ubiqua, board DDD, modelo de tabelas) e
as evidencias de testes e vulnerabilidades continuam em `docs/`.

## Padroes utilizados

- Clean Architecture
- Arquitetura Hexagonal
- SOLID
- Separacao entre dominio e persistencia JPA
- Value Objects para validacoes de CPF, CNPJ, Email, Placa, Senha e Telefone
- Repositories como portas do dominio
- Use cases para orquestracao das regras de aplicacao
- DTOs e mappers nos adapters de entrada e saida
- Tratamento global de excecoes
