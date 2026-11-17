package br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;

import java.time.Instant;

public record HistoricoOrdemServicoResponse(
        Instant ocorridoEm,
        String situacaoAnterior,
        String situacaoNova,
        String evento
) {
    public static HistoricoOrdemServicoResponse de(EntradaHistorico entrada) {
        return new HistoricoOrdemServicoResponse(entrada.ocorridoEm(), entrada.situacaoAnterior(),
                entrada.situacaoNova(), entrada.evento());
    }
}
