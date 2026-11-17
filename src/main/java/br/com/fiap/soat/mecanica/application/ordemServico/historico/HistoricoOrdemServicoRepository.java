package br.com.fiap.soat.mecanica.application.ordemServico.historico;

import java.util.List;
import java.util.UUID;

public interface HistoricoOrdemServicoRepository {

    /** Idempotente: gravar a mesma entrada (mesmo messageId) duas vezes não duplica. */
    void registrar(EntradaHistorico entrada);

    /** Em ordem cronológica. */
    List<EntradaHistorico> listarPorOsId(UUID osId);
}
