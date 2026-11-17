package br.com.fiap.soat.mecanica.application.ordemServico.usecase;

import br.com.fiap.soat.mecanica.application.saga.OrquestradorSaga;
import br.com.fiap.soat.mecanica.domain.enums.SituacaoOrdemServicoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static br.com.fiap.soat.mecanica.config.observability.BusinessEventLogger.ordemServicoCancelada;

@Service
@RequiredArgsConstructor
public class CancelarOrdemServicoPorDesistenciaUseCase {

    private final OrdemServicoRepository ordemServicoRepository;
    private final OrquestradorSaga orquestradorSaga;

    /** Cancela a OS e dispara as compensações da saga (liberar peças, cancelar orçamento). */
    @Transactional
    public OrdemServico executar(UUID osId) {

        OrdemServico os = ordemServicoRepository.buscarPorId(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de serviço não encontrada"));

        SituacaoOrdemServicoEnum situacaoAnterior = os.getSituacao();
        OrdemServico resultado = orquestradorSaga.cancelarPorDesistencia(os);

        if (resultado.getSituacao() != situacaoAnterior) {
            ordemServicoCancelada(resultado.getId());
        }
        return resultado;
    }
}
