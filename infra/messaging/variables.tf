variable "aws_region" {
  description = "Região da conta AWS Academy"
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Prefixo compartilhado entre os repositórios"
  type        = string
  default     = "mecanica"
}

variable "visibility_timeout_seconds" {
  description = "Tempo que a mensagem fica invisível enquanto o OS processa. Folga sobre os ~10 s esperados"
  type        = number
  default     = 60
}

variable "max_receive_count" {
  description = "Tentativas antes de a mensagem ir para a DLQ"
  type        = number
  default     = 5
}
