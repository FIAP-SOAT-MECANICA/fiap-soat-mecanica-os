variable "vpc_id" {
  description = "ID da VPC onde o RDS será provisionado"
  type        = string
}

variable "subnet_ids" {
  description = "IDs das subnets para o DB Subnet Group"
  type        = list(string)
}

variable "db_username" {
  description = "Nome do usuário do Banco de dados"
  type        = string
  default     = "postgres"
}

variable "db_name" {
  description = "Nome do Banco de dados"
  type        = string
}

variable "db_port" {
  description = "Porta do Banco de dados"
  type        = number
  default     = 5432
}

variable "db_engine_version" {
  description = "Versão do Banco de dados"
  type        = string
  default     = "17"
}

variable "db_instance_class" {
  description = "Instância que o RDS (Banco de dados) irá utilizar para ser processada"
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "Armazenamento alocado para o RDS, em GB"
  type        = number
  default     = 10
}

variable "publicly_accessible" {
  description = "Endereço público para o RDS. false: o banco só é alcançado de dentro da VPC, pelo SG do EKS"
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Pular o snapshot final no destroy. true porque o AWS Academy é recriado a cada sessão e snapshot geraria custo sem uso; em produção real seria false"
  type        = bool
  default     = true
}

variable "multi_az" {
  description = "Réplica standby em outra AZ. false para caber no orçamento do AWS Academy; em produção real seria true"
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Bloqueia o destroy do RDS. false porque o laboratório precisa ser destruído e recriado; em produção real seria true"
  type        = bool
  default     = false
}

variable "allowed_security_group_id" {
  description = "Id do Security Group para acesso ao RDS"
  type        = string
}
