package br.com.fiap.soat.mecanica.application.saga.contrato;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Valores do campo "type" do envelope (RFC-002, seção de contratos). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TiposDeMensagem {

    // Comandos OS -> Execução (fila mecanica-execucao-comandos)
    public static final String ABRIR_DIAGNOSTICO = "AbrirDiagnostico";
    public static final String RESERVAR_PECAS = "ReservarPecas";
    public static final String LIBERAR_RESERVA_PECAS = "LiberarReservaPecas";
    public static final String INICIAR_EXECUCAO = "IniciarExecucao";

    // Eventos Execução -> OS (tópico mecanica-execucao-eventos)
    public static final String DIAGNOSTICO_ABERTO = "DiagnosticoAberto";
    public static final String DIAGNOSTICO_CONCLUIDO = "DiagnosticoConcluido";
    public static final String PECAS_RESERVADAS = "PecasReservadas";
    public static final String RESERVA_PECAS_RECUSADA = "ReservaPecasRecusada";
    public static final String RESERVA_PECAS_LIBERADA = "ReservaPecasLiberada";
    public static final String EXECUCAO_INICIADA = "ExecucaoIniciada";
    public static final String EXECUCAO_FINALIZADA = "ExecucaoFinalizada";

    // Comandos OS -> Billing (fila mecanica-billing-comandos). SolicitarPagamento é o nome usado
    // pelo Billing na infra dele; o plano chamava de CobrarPagamento.
    public static final String GERAR_ORCAMENTO = "GerarOrcamento";
    public static final String CANCELAR_ORCAMENTO = "CancelarOrcamento";
    public static final String SOLICITAR_PAGAMENTO = "SolicitarPagamento";
    public static final String CANCELAR_COBRANCA = "CancelarCobranca";

    // Eventos Billing -> OS (tópico mecanica-billing-eventos)
    public static final String ORCAMENTO_GERADO = "OrcamentoGerado";
    public static final String ORCAMENTO_APROVADO = "OrcamentoAprovado";
    public static final String ORCAMENTO_RECUSADO = "OrcamentoRecusado";
    public static final String PAGAMENTO_CONFIRMADO = "PagamentoConfirmado";
    public static final String PAGAMENTO_RECUSADO = "PagamentoRecusado";

    // Interno: transição de situação da OS, gravada no histórico (DynamoDB)
    public static final String SITUACAO_ALTERADA = "SituacaoOrdemServicoAlterada";
}
