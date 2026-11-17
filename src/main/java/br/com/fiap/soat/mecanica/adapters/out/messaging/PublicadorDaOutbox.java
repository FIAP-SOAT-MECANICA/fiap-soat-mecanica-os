package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.HistoricoOrdemServicoRepository;
import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.OutboxRepository;
import br.com.fiap.soat.mecanica.application.saga.PublicadorDeComandos;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.SpanKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;

/**
 * Esvazia a outbox (tarefa 5.5). Cada lote roda numa transação: as linhas ficam travadas
 * (FOR UPDATE SKIP LOCKED) enquanto são publicadas, então duas réplicas não publicam a mesma.
 *
 * Publicar e marcar não são atômicos: se o pod cair depois de publicar e antes do commit, a
 * linha volta a ficar pendente e é publicada de novo com o mesmo messageId. É a entrega
 * "pelo menos uma vez"; o consumidor deduplica. O inverso (marcar sem publicar) não acontece.
 *
 * Registrado como bean em {@link PublicadorDaOutboxConfig}, que também agenda a execução.
 */
@Slf4j
public class PublicadorDaOutbox {

    private final OutboxRepository outboxRepository;
    private final PublicadorDeComandos publicadorDeComandos;
    private final HistoricoOrdemServicoRepository historicoRepository;
    private final TransactionOperations transacao;
    private final int tamanhoLote;

    public PublicadorDaOutbox(OutboxRepository outboxRepository,
                              PublicadorDeComandos publicadorDeComandos,
                              HistoricoOrdemServicoRepository historicoRepository,
                              TransactionOperations transacao,
                              int tamanhoLote) {
        this.outboxRepository = outboxRepository;
        this.publicadorDeComandos = publicadorDeComandos;
        this.historicoRepository = historicoRepository;
        this.transacao = transacao;
        this.tamanhoLote = tamanhoLote;
    }

    /** @return quantas mensagens foram publicadas neste lote */
    public int publicarLote() {
        Integer publicadas = transacao.execute(status -> {
            List<MensagemOutbox> pendentes = outboxRepository.travarPendentes(tamanhoLote);
            int ok = 0;
            for (MensagemOutbox mensagem : pendentes) {
                if (publicar(mensagem)) {
                    ok++;
                }
            }
            return ok;
        });
        return publicadas == null ? 0 : publicadas;
    }

    private boolean publicar(MensagemOutbox mensagem) {
        try {
            RastreamentoMensagens.executarComo("publicar " + mensagem.tipo(), SpanKind.PRODUCER,
                    mensagem.traceparent(), () -> {
                        entregar(mensagem);
                        return null;
                    });
            outboxRepository.marcarPublicada(mensagem.id());
            return true;
        } catch (RuntimeException e) {
            // Fica pendente para o próximo lote. Não interrompe as demais do lote.
            log.warn("Falha ao publicar mensagem da outbox; nova tentativa no próximo ciclo. id={} tipo={} destino={}",
                    mensagem.id(), mensagem.tipo(), mensagem.destino(), e);
            outboxRepository.registrarFalha(mensagem.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private void entregar(MensagemOutbox mensagem) {
        if (mensagem.destino() == DestinoMensagem.HISTORICO) {
            historicoRepository.registrar(paraHistorico(mensagem));
        } else {
            publicadorDeComandos.publicar(mensagem);
        }
    }

    private static EntradaHistorico paraHistorico(MensagemOutbox mensagem) {
        JsonNode payload = mensagem.payload();
        return new EntradaHistorico(
                mensagem.osId(),
                payload.hasNonNull("ocorridoEm") ? Instant.parse(payload.get("ocorridoEm").asText()) : mensagem.criadoEm(),
                mensagem.id(),
                texto(payload, "situacaoAnterior"),
                texto(payload, "situacaoNova"),
                texto(payload, "evento"));
    }

    private static String texto(JsonNode payload, String campo) {
        return payload.hasNonNull(campo) ? payload.get(campo).asText() : null;
    }
}
