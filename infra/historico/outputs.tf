output "historico_table_name" {
  description = "Nome da tabela de histórico, usado pela aplicação na tarefa 6.4"
  value       = aws_dynamodb_table.historico.name
}

output "historico_table_arn" {
  description = "ARN da tabela, para permissões de acesso"
  value       = aws_dynamodb_table.historico.arn
}