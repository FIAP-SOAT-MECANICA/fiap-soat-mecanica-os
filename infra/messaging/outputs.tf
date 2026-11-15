output "response_queue_url" {
  value = aws_sqs_queue.respostas.url
}

output "event_topic_arn" {
  value = aws_sns_topic.eventos.arn
}

output "execucao_command_queue_url" {
  description = "Para onde o OS manda comandos (lido do SSM do Execução)"
  value       = nonsensitive(data.aws_ssm_parameter.execucao_command_queue_url.value)
}
