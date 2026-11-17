package br.com.fiap.soat.mecanica.application.servico;

import br.com.fiap.soat.mecanica.domain.servico.Servico;
import br.com.fiap.soat.mecanica.domain.servico.ServicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InativarServicoUseCase {

    private final ServicoRepository servicoRepository;
    private final BuscarServicoPorIdUseCase buscarServicoPorIdUseCase;

    public Servico executar(UUID id) {
        Servico servico = buscarServicoPorIdUseCase.executar(id);

        servico.inativar();

        return servicoRepository.salvar(servico);
    }
}
