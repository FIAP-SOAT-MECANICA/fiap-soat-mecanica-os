data "aws_ssm_parameter" "execucao_event_topic_arn" {
  name = "/${var.project}/execucao/event-topic-arn"
}

data "aws_ssm_parameter" "execucao_command_queue_url" {
  name = "/${var.project}/execucao/command-queue-url"
}

# Billing: publicados pelo repositório fiap-soat-mecanica-billing (infra/messaging.tf).
# Com assinar_billing = false o módulo aplica sem o Billing existir.
data "aws_ssm_parameter" "billing_event_topic_arn" {
  count = var.assinar_billing ? 1 : 0
  name  = "/${var.project}/billing/event-topic-arn"
}

data "aws_ssm_parameter" "billing_command_queue_url" {
  count = var.assinar_billing ? 1 : 0
  name  = "/${var.project}/billing/command-queue-url"
}

locals {
  # Tópicos autorizados a entregar na fila de respostas do OS.
  topicos_assinados = concat(
    [data.aws_ssm_parameter.execucao_event_topic_arn.value],
    [for p in data.aws_ssm_parameter.billing_event_topic_arn : p.value]
  )
}

resource "aws_sqs_queue" "respostas_dlq" {
  name                      = "${var.project}-os-respostas-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "respostas" {
  name                       = "${var.project}-os-respostas"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.respostas_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "respostas_dlq" {
  queue_url = aws_sqs_queue.respostas_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.respostas.arn]
  })
}

resource "aws_sqs_queue_policy" "respostas" {
  queue_url = aws_sqs_queue.respostas.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PermiteTopicosAssinados"
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.respostas.arn
      Condition = {
        ArnEquals = { "aws:SourceArn" = local.topicos_assinados }
      }
    }]
  })
}

resource "aws_sns_topic_subscription" "execucao_eventos" {
  topic_arn            = data.aws_ssm_parameter.execucao_event_topic_arn.value
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.respostas.arn
  raw_message_delivery = true

  depends_on = [aws_sqs_queue_policy.respostas]
}

# Eventos do Billing na mesma fila: um consumidor, uma inbox, uma DLQ. O orquestrador roteia por "type".
resource "aws_sns_topic_subscription" "billing_eventos" {
  count = var.assinar_billing ? 1 : 0

  topic_arn            = data.aws_ssm_parameter.billing_event_topic_arn[0].value
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.respostas.arn
  raw_message_delivery = true

  depends_on = [aws_sqs_queue_policy.respostas]
}

resource "aws_sns_topic" "eventos" {
  name = "${var.project}-os-eventos"
}

resource "aws_ssm_parameter" "response_queue_url" {
  name  = "/${var.project}/os/response-queue-url"
  type  = "String"
  value = aws_sqs_queue.respostas.url
}

resource "aws_ssm_parameter" "event_topic_arn" {
  name  = "/${var.project}/os/event-topic-arn"
  type  = "String"
  value = aws_sns_topic.eventos.arn
}
