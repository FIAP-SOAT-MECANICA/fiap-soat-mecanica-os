package br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto;

import br.com.fiap.soat.mecanica.domain.saga.PassoSagaEnum;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServico;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SagaOrdemServicoResponse(
        UUID sagaId,
        UUID osId,
        String passoAtual,
        String estado,
        List<String> passosConcluidos,
        List<String> passosCompensados,
        UUID orcamentoId,
        String motivo,
        Instant atualizadoEm
) {
    public static SagaOrdemServicoResponse de(SagaOrdemServico saga) {
        return new SagaOrdemServicoResponse(
                saga.getSagaId(),
                saga.getOsId(),
                saga.getPassoAtual().name(),
                saga.getEstado().name(),
                saga.getPassosConcluidos().stream().map(PassoSagaEnum::name).toList(),
                saga.getPassosCompensados().stream().map(PassoSagaEnum::name).toList(),
                saga.getOrcamentoId(),
                saga.getMotivo(),
                saga.getAtualizadoEm());
    }
}
