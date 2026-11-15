package br.com.fiap.soat.mecanica.application.ordemServico.usecase;

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

    @Transactional
    public OrdemServico executar(UUID osId) {

        OrdemServico os = ordemServicoRepository.buscarPorId(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de serviço não encontrada"));

        os.cancelarPorDesistencia();

        OrdemServico ordemServicoSalva = ordemServicoRepository.salvar(os);
        ordemServicoCancelada(ordemServicoSalva.getId());
        return ordemServicoSalva;
    }
}
