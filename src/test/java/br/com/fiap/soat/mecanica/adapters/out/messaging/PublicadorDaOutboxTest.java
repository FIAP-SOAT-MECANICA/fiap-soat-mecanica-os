package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.HistoricoOrdemServicoRepository;
import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.InboxRepository;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.MensagemSaida;
import br.com.fiap.soat.mecanica.application.saga.OutboxRepository;
import br.com.fiap.soat.mecanica.application.saga.ProcessadorDeEventos;
import br.com.fiap.soat.mecanica.application.saga.PublicadorDeComandos;
import br.com.fiap.soat.mecanica.application.saga.ResultadoProcessamento;
import br.com.fiap.soat.mecanica.application.saga.TratadorDeEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Critério de pronto da 5.5: uma falha simulada na publicação não perde o evento, e o reenvio
 * é detectado como duplicado pelo consumidor.
 */
class PublicadorDaOutboxTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OutboxEmMemoria outbox = new OutboxEmMemoria();
    private final HistoricoEmMemoria historico = new HistoricoEmMemoria();

    @Test
    @DisplayName("Falha ao publicar mantém a mensagem pendente e a próxima rodada publica com o mesmo messageId")
    void naoDevePerderMensagemQuandoPublicacaoFalha() {
        UUID id = outbox.registrar(comando("ReservarPecas"));
        PublicadorQueFalhaNaPrimeira sqs = new PublicadorQueFalhaNaPrimeira();
        PublicadorDaOutbox publicador = novoPublicador(sqs);

        assertThat(publicador.publicarLote()).isZero();
        assertThat(outbox.pendentes()).containsExactly(id);
        assertThat(outbox.falhas.get(id)).isEqualTo(1);

        assertThat(publicador.publicarLote()).isEqualTo(1);
        assertThat(outbox.pendentes()).isEmpty();

        // A primeira tentativa chegou a enviar antes de falhar (falha depois do envio: pior caso).
        assertThat(sqs.enviadas).extracting(MensagemOutbox::id).containsExactly(id, id);
    }

    @Test
    @DisplayName("O reenvio com o mesmo messageId é descartado como duplicado pelo consumidor")
    void reenvioDeveSerDuplicadoNoConsumidor() {
        outbox.registrar(comando("ReservarPecas"));
        PublicadorQueFalhaNaPrimeira sqs = new PublicadorQueFalhaNaPrimeira();
        PublicadorDaOutbox publicador = novoPublicador(sqs);
        publicador.publicarLote();
        publicador.publicarLote();

        AtomicInteger efeitos = new AtomicInteger();
        ProcessadorDeEventos consumidor = new ProcessadorDeEventos(new InboxEmMemoria(), List.of(
                new TratadorDeEventos() {
                    @Override
                    public Set<String> tipos() {
                        return Set.of("ReservarPecas");
                    }

                    @Override
                    public void tratar(EventoRecebido evento) {
                        efeitos.incrementAndGet();
                    }
                }));

        List<ResultadoProcessamento> resultados = sqs.enviadas.stream()
                .map(m -> consumidor.processar(new EventoRecebido(m.id(), m.tipo(), m.sagaId(), m.osId(),
                        m.correlationId(), m.causationId(), m.payload())))
                .toList();

        assertThat(resultados).containsExactly(ResultadoProcessamento.PROCESSADO, ResultadoProcessamento.DUPLICADO);
        assertThat(efeitos).hasValue(1);
    }

    @Test
    @DisplayName("Mensagem de HISTORICO vai para o repositório de histórico, não para o SQS")
    void deveEncaminharHistorico() {
        UUID osId = UUID.randomUUID();
        UUID id = outbox.registrar(new MensagemSaida(DestinoMensagem.HISTORICO, "SituacaoOrdemServicoAlterada",
                UUID.randomUUID(), osId, null, null, Map.of("osId", osId, "situacaoAnterior", "RECEBIDA",
                "situacaoNova", "EM_DIAGNOSTICO", "evento", "DiagnosticoAberto",
                "ocorridoEm", "2026-11-17T10:00:00.123Z")));
        PublicadorQueFalhaNaPrimeira sqs = new PublicadorQueFalhaNaPrimeira();

        assertThat(novoPublicador(sqs).publicarLote()).isEqualTo(1);

        assertThat(sqs.enviadas).isEmpty();
        assertThat(historico.entradas).singleElement().satisfies(entrada -> {
            assertThat(entrada.messageId()).isEqualTo(id);
            assertThat(entrada.situacaoAnterior()).isEqualTo("RECEBIDA");
            assertThat(entrada.situacaoNova()).isEqualTo("EM_DIAGNOSTICO");
            assertThat(entrada.evento()).isEqualTo("DiagnosticoAberto");
            assertThat(entrada.ocorridoEm()).isEqualTo(Instant.parse("2026-11-17T10:00:00.123Z"));
        });
    }

    @Test
    @DisplayName("Uma mensagem que falha não impede as outras do lote")
    void falhaNaoBloqueiaOLote() {
        UUID primeira = outbox.registrar(comando("ReservarPecas"));
        UUID segunda = outbox.registrar(comando("IniciarExecucao"));
        PublicadorQueFalhaNaPrimeira sqs = new PublicadorQueFalhaNaPrimeira();

        assertThat(novoPublicador(sqs).publicarLote()).isEqualTo(1);

        assertThat(outbox.pendentes()).containsExactly(primeira);
        assertThat(outbox.publicadas).contains(segunda);
    }

    private PublicadorDaOutbox novoPublicador(PublicadorDeComandos sqs) {
        return new PublicadorDaOutbox(outbox, sqs, historico, TransactionOperations.withoutTransaction(), 10);
    }

    private MensagemSaida comando(String tipo) {
        UUID osId = UUID.randomUUID();
        return new MensagemSaida(DestinoMensagem.EXECUCAO, tipo, UUID.randomUUID(), osId, "corr", null,
                Map.of("osId", osId));
    }

    /** Envia, mas na primeira chamada lança depois do envio: simula queda entre publicar e marcar. */
    private static class PublicadorQueFalhaNaPrimeira implements PublicadorDeComandos {
        private final List<MensagemOutbox> enviadas = new ArrayList<>();
        private boolean falhou;

        @Override
        public void publicar(MensagemOutbox mensagem) {
            enviadas.add(mensagem);
            if (!falhou) {
                falhou = true;
                throw new IllegalStateException("timeout depois do envio");
            }
        }
    }

    private class OutboxEmMemoria implements OutboxRepository {
        private final Map<UUID, MensagemOutbox> linhas = new LinkedHashMap<>();
        private final Set<UUID> publicadas = new HashSet<>();
        private final Map<UUID, Integer> falhas = new LinkedHashMap<>();

        @Override
        public UUID registrar(MensagemSaida m) {
            UUID id = UUID.randomUUID();
            linhas.put(id, new MensagemOutbox(id, m.destino(), m.tipo(), m.sagaId(), m.osId(), m.correlationId(),
                    m.causationId(), mapper.valueToTree(m.payload()), null, Instant.now()));
            return id;
        }

        @Override
        public List<MensagemOutbox> travarPendentes(int limite) {
            return linhas.values().stream().filter(m -> !publicadas.contains(m.id())).limit(limite).toList();
        }

        @Override
        public void marcarPublicada(UUID id) {
            publicadas.add(id);
        }

        @Override
        public void registrarFalha(UUID id, String erro) {
            falhas.merge(id, 1, Integer::sum);
        }

        List<UUID> pendentes() {
            return travarPendentes(100).stream().map(MensagemOutbox::id).toList();
        }
    }

    private static class HistoricoEmMemoria implements HistoricoOrdemServicoRepository {
        private final List<EntradaHistorico> entradas = new ArrayList<>();

        @Override
        public void registrar(EntradaHistorico entrada) {
            entradas.add(entrada);
        }

        @Override
        public List<EntradaHistorico> listarPorOsId(UUID osId) {
            return entradas.stream().filter(e -> e.osId().equals(osId)).toList();
        }
    }

    private static class InboxEmMemoria implements InboxRepository {
        private final Set<UUID> vistas = new HashSet<>();

        @Override
        public boolean registrarSeNova(UUID messageId, String tipo) {
            return vistas.add(messageId);
        }
    }
}
