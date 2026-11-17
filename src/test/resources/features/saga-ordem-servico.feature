# language: pt
Funcionalidade: Saga orquestrada da ordem de serviço
  O OS Service orquestra Execução e Billing por mensagens. Os eventos dos outros serviços
  são simulados entrando pelo mesmo ponto que o consumidor SQS usa (inbox + orquestrador),
  e os comandos são conferidos na outbox, de onde o publicador os envia.

  Contexto:
    Dado um veículo cadastrado com a placa "ABC1D23"

  Cenário: Caminho feliz, da abertura à entrega
    Quando o mecânico abre uma ordem de serviço
    Então a OS está "RECEBIDA"
    E a saga está no passo "ABERTURA" com estado "EM_ANDAMENTO"
    E o comando "AbrirDiagnostico" foi gravado para "EXECUCAO"
    Quando o Execução publica "DiagnosticoAberto"
    Então a OS está "EM_DIAGNOSTICO"
    Quando o Execução publica "DiagnosticoConcluido"
    Então a OS está "AGUARDANDO_APROVACAO"
    E o comando "GerarOrcamento" foi gravado para "BILLING"
    Quando o Billing publica "OrcamentoAprovado"
    Então o comando "ReservarPecas" foi gravado para "EXECUCAO"
    Quando o Execução publica "PecasReservadas"
    Então a OS está "EM_EXECUCAO"
    E o comando "IniciarExecucao" foi gravado para "EXECUCAO"
    Quando o Execução publica "ExecucaoFinalizada"
    Então a OS está "FINALIZADA"
    E o comando "SolicitarPagamento" foi gravado para "BILLING"
    Quando o Billing publica "PagamentoConfirmado"
    Então a OS está "ENTREGUE"
    E a saga está no passo "ENTREGA" com estado "CONCLUIDA"
    E a saga concluiu os passos "ABERTURA, DIAGNOSTICO, ORCAMENTO, RESERVA, EXECUCAO, COBRANCA, ENTREGA"
    Quando o publicador da outbox roda
    Então nenhuma mensagem fica pendente na outbox
    E o histórico da OS mostra "RECEBIDA, EM_DIAGNOSTICO, AGUARDANDO_APROVACAO, EM_EXECUCAO, FINALIZADA, ENTREGUE"

  Cenário: Evento entregue duas vezes não avança a saga duas vezes
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoAberto"
    E o mesmo evento é entregue de novo
    Então a última entrega foi descartada como duplicada
    E a OS está "EM_DIAGNOSTICO"
    E a saga está no passo "DIAGNOSTICO" com estado "EM_ANDAMENTO"

  Cenário: Evento adiantado volta para a fila em vez de ser perdido
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoConcluido" antes da hora
    Então o evento é recusado para nova tentativa
    E a saga está no passo "ABERTURA" com estado "EM_ANDAMENTO"

  Cenário: Sem estoque, o orçamento é cancelado e a OS volta a aguardar aprovação
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoAberto"
    E o Execução publica "DiagnosticoConcluido"
    E o Billing publica "OrcamentoAprovado"
    E o Execução publica "ReservaPecasRecusada"
    Então o comando "CancelarOrcamento" foi gravado para "BILLING"
    E a OS está "AGUARDANDO_APROVACAO"
    E a saga está no passo "RESERVA" com estado "COMPENSADA"
    E a saga compensou os passos "ORCAMENTO"

  Cenário: Desistência durante a execução compensa nos dois serviços
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoAberto"
    E o Execução publica "DiagnosticoConcluido"
    E o Billing publica "OrcamentoAprovado"
    E o Execução publica "PecasReservadas"
    E o cliente desiste da ordem de serviço
    Então o comando "LiberarReservaPecas" foi gravado para "EXECUCAO"
    E o comando "CancelarOrcamento" foi gravado para "BILLING"
    E a OS está "CANCELADA"
    E a saga está no passo "EXECUCAO" com estado "CANCELADA"
    E a saga compensou os passos "RESERVA, ORCAMENTO, DIAGNOSTICO"

  Cenário: Pagamento recusado cancela a cobrança e a OS fica finalizada
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoAberto"
    E o Execução publica "DiagnosticoConcluido"
    E o Billing publica "OrcamentoAprovado"
    E o Execução publica "PecasReservadas"
    E o Execução publica "ExecucaoFinalizada"
    E o Billing publica "PagamentoRecusado"
    Então o comando "CancelarCobranca" foi gravado para "BILLING"
    E a OS está "FINALIZADA"
    E a saga está no passo "COBRANCA" com estado "COMPENSADA"
    E a saga compensou os passos "COBRANCA"

  Cenário: Orçamento recusado cancela a OS
    Quando o mecânico abre uma ordem de serviço
    E o Execução publica "DiagnosticoAberto"
    E o Execução publica "DiagnosticoConcluido"
    E o Billing publica "OrcamentoRecusado"
    Então a OS está "CANCELADA"
    E a saga está no passo "ORCAMENTO" com estado "CANCELADA"
