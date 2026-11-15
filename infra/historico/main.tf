# Cada item é uma transição de estado de uma OS.
# osId (partição) agrupa o histórico de uma OS; ocorridoEm (ordenação) põe as transições em ordem.
# Formato de ocorridoEm: "<ISO-8601 UTC com milissegundos>#<messageId>",
# ex.: "2026-11-15T19:00:00.123Z#9f1c...". O texto ISO ordena igual ao tempo, e o messageId
# impede que duas transições no mesmo milissegundo se sobrescrevam.
resource "aws_dynamodb_table" "historico" {
  name         = "os-historico"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "osId"
  range_key    = "ocorridoEm"

  attribute {
    name = "osId"
    type = "S"
  }

  attribute {
    name = "ocorridoEm"
    type = "S"
  }
}

resource "aws_ssm_parameter" "historico_table_name" {
  name  = "/${var.project}/os/historico-table-name"
  type  = "String"
  value = aws_dynamodb_table.historico.name
}    
