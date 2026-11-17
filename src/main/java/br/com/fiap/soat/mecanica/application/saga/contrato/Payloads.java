package br.com.fiap.soat.mecanica.application.saga.contrato;

import br.com.fiap.soat.mecanica.domain.saga.ItemDiagnostico;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads das mensagens. Todos levam osId: o Execução rejeita payload.osId diferente do envelope.
 * cycleId segue o contrato de ciclos de reserva do Execução (RFC-F4-02 daquele repositório).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Payloads {

    // ---- comandos para o Execução ----

    public record AbrirDiagnostico(UUID osId, UUID veiculoId, String placa) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReservarPecas(UUID osId, UUID cycleId, List<PecaQuantidade> pecas) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiberarReservaPecas(UUID osId, UUID cycleId) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IniciarExecucao(UUID osId, UUID cycleId) { }

    public record PecaQuantidade(UUID pecaId, int qtd) { }

    // ---- comandos para o Billing ----

    /** clienteId é exigido pelo Orcamento do Billing e só o OS conhece. nomeServico idem (catálogo do OS). */
    public record GerarOrcamento(UUID osId, UUID clienteId, List<ItemOrcamento> itens) { }

    public record ItemOrcamento(UUID servicoId, String nomeServico, BigDecimal precoMdo,
                                List<ItemDiagnostico.PecaDiagnostico> pecas) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReferenciaOrcamento(UUID osId, UUID orcamentoId) { }

    // ---- eventos recebidos ----

    public record DiagnosticoConcluido(UUID osId, List<ItemDiagnostico> itens) { }

    public record ReservaPecasRecusada(UUID osId, List<PecaQuantidade> faltantes) { }

    /** OrcamentoGerado/Aprovado/Recusado do Billing: {osId, orcamentoId}. */
    public record DecisaoOrcamento(UUID osId, UUID orcamentoId) { }

    // ---- interno: histórico ----

    public record SituacaoAlterada(UUID osId, String situacaoAnterior, String situacaoNova, String evento,
                                   Instant ocorridoEm) { }
}
