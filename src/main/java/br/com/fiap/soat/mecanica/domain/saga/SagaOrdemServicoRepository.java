package br.com.fiap.soat.mecanica.domain.saga;

import java.util.Optional;
import java.util.UUID;

public interface SagaOrdemServicoRepository {

    /** Insere ou atualiza. */
    void salvar(SagaOrdemServico saga);

    Optional<SagaOrdemServico> buscarPorOsId(UUID osId);

    /**
     * Busca travando a linha até o fim da transação (SELECT ... FOR UPDATE). Dois eventos da mesma
     * OS processados ao mesmo tempo por réplicas diferentes ficam em fila em vez de se atropelarem.
     */
    Optional<SagaOrdemServico> buscarPorOsIdParaAtualizar(UUID osId);
}
