package br.com.fiap.soat.mecanica.application.ordemServico.historico;

import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BuscarHistoricoOrdemServicoUseCase {

    private final OrdemServicoRepository ordemServicoRepository;
    private final HistoricoOrdemServicoRepository historicoRepository;

    public List<EntradaHistorico> executar(UUID osId) {
        if (ordemServicoRepository.buscarPorId(osId).isEmpty()) {
            throw new RecursoNaoEncontradoException("Ordem de serviço não encontrada");
        }
        return historicoRepository.listarPorOsId(osId);
    }
}
