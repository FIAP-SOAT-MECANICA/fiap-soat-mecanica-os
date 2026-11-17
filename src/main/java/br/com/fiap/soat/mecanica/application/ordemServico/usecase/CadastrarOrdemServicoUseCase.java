package br.com.fiap.soat.mecanica.application.ordemServico.usecase;

import br.com.fiap.soat.mecanica.application.saga.OrquestradorSaga;
import br.com.fiap.soat.mecanica.application.security.CurrentUserPort;
import br.com.fiap.soat.mecanica.domain.enums.CargoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import br.com.fiap.soat.mecanica.domain.usuario.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static br.com.fiap.soat.mecanica.config.observability.BusinessEventLogger.ordemServicoCriada;

@Service
@RequiredArgsConstructor
public class CadastrarOrdemServicoUseCase {

    private final OrdemServicoRepository repository;
    private final CurrentUserPort currentUser;
    private final OrquestradorSaga orquestradorSaga;

    /**
     * Passo 1 da saga: OS em RECEBIDA, instância da saga e AbrirDiagnostico na outbox,
     * tudo na mesma transação. Se qualquer parte falhar, nada fica gravado.
     */
    @Transactional
    public OrdemServico executar(String observacao, UUID veiculoId) {

        Usuario usuario = currentUser.get();

        if (usuario.getCargoEnum() != CargoEnum.MECANICO) {
            throw new RegraNegocioException("Somente mecânicos podem criar OS");
        }

        OrdemServico os = new OrdemServico(observacao, veiculoId, usuario.getId());
        OrdemServico ordemServicoSalva = repository.salvar(os);
        orquestradorSaga.iniciar(ordemServicoSalva);
        ordemServicoCriada(ordemServicoSalva.getId(), veiculoId, usuario.getId());
        return ordemServicoSalva;
    }
}
