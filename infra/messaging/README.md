# Mensageria do OS Service

Infraestrutura de mensageria da saga orquestrada. Root module próprio, com state separado do banco
(`infra/rds/`) e do cluster de CI (`infra/k8s/`): um `destroy` aqui não afeta os outros.

## O que é criado

| Recurso | Nome | Para que serve |
| --- | --- | --- |
| Fila SQS | `mecanica-os-respostas` | Caixa de entrada do OS: recebe os eventos do Execução |
| Fila SQS (DLQ) | `mecanica-os-respostas-dlq` | Recebe a mensagem que falhou 5 vezes; retenção de 14 dias |
| Política da fila | — | Só o tópico `mecanica-execucao-eventos` pode entregar na fila |
| Assinatura SNS → SQS | — | Liga o tópico do Execução à fila do OS, com `raw_message_delivery` |
| Tópico SNS | `mecanica-os-eventos` | Eventos de domínio publicados pelo OS |
| Parâmetros SSM | `/mecanica/os/response-queue-url`, `/mecanica/os/event-topic-arn` | Onde os outros serviços descobrem os recursos do OS |

Também lê do SSM, sem criar, o que o Execução publica:
`/mecanica/execucao/event-topic-arn` e `/mecanica/execucao/command-queue-url`.

## Ordem de deploy

O Execução precisa ter feito o `apply` **antes** deste módulo. Os parâmetros SSM que ele publica são
lidos aqui com `data`; se não existirem, o `plan` falha. Como a AWS Academy é recriada a cada sessão,
a ordem vale a cada nova sessão:

```
fiap-soat-mecanica-k8s → fiap-soat-mecanica-execucao + fiap-soat-mecanica-billing → fiap-soat-mecanica-os (infra/messaging)
```

## Billing

O tópico `mecanica-billing-eventos` é assinado **na mesma fila** `mecanica-os-respostas` (revisão da
decisão anterior de fila separada): o OS tem um único consumidor, uma única inbox e uma única DLQ, e o
orquestrador roteia pelo `type` do envelope. A política da fila aceita os dois tópicos (`aws:SourceArn`).
O OS também lê `/mecanica/billing/command-queue-url` para mandar `GerarOrcamento`, `CancelarOrcamento`,
`SolicitarPagamento` e `CancelarCobranca`.

Com isso o Billing também precisa ter feito o `apply` antes deste módulo. Para aplicar sem ele, use
`-var assinar_billing=false` (no CD: variável de repositório `ASSINAR_BILLING=false`).

## Como rodar

O `bucket` e a `region` do state ficam fora do código, porque o nome do bucket depende da conta da
AWS Academy. A `key` (`os/messaging/terraform.tfstate`) já está em `versions.tf`.

```bash
cd infra/messaging
terraform init -backend-config="bucket=<bucket-de-state>" -backend-config="region=us-east-1"
terraform plan
terraform apply
```

Validação sem credenciais da AWS:

```bash
terraform init -backend=false
terraform fmt -check
terraform validate
```
