package br.com.fiap.soat.mecanica.domain.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Item de DiagnosticoConcluido: insumo do orçamento (Billing) e da reserva (Execução). */
public record ItemDiagnostico(UUID servicoId, BigDecimal precoMdo, List<PecaDiagnostico> pecas) {

    public ItemDiagnostico {
        pecas = pecas == null ? List.of() : List.copyOf(pecas);
    }

    public record PecaDiagnostico(UUID pecaId, int qtd, BigDecimal valorUnit) {
    }
}
