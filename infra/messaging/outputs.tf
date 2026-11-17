output "response_queue_url" {
  value = aws_sqs_queue.respostas.url
}

output "event_topic_arn" {
  value = aws_sns_topic.eventos.arn
}

output "billing_command_queue_url" {
  description = "Para onde o OS manda comandos ao Billing (lido do SSM do Billing)"
  value       = var.assinar_billing ? nonsensitive(data.aws_ssm_parameter.billing_command_queue_url[0].value) : ""
}

output "execucao_command_queue_url" {
  description = "Para onde o OS manda comandos (lido do SSM do Execução)"
  value       = nonsensitive(data.aws_ssm_parameter.execucao_command_queue_url.value)
}
