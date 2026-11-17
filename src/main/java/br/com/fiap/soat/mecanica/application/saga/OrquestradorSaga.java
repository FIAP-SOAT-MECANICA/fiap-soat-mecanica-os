package br.com.fiap.soat.mecanica.application.saga;

import br.com.fiap.soat.mecanica.application.ordemServico.usecase.NotificarAlteracaoSituacaoOrdemServicoUseCase;
import br.com.fiap.soat.mecanica.application.saga.contrato.Payloads;
import br.com.fiap.soat.mecanica.domain.enums.SituacaoOrdemServicoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import br.com.fiap.soat.mecanica.domain.saga.EstadoSagaEnum;
import br.com.fiap.soat.mecanica.domain.saga.PassoSagaEnum;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServico;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServicoRepository;
import br.com.fiap.soat.mecanica.domain.servico.Servico;
import br.com.fiap.soat.mecanica.domain.servico.ServicoRepository;
import br.com.fiap.soat.mecanica.domain.veiculo.Veiculo;
import br.com.fiap.soat.mecanica.domain.veiculo.VeiculoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static br.com.fiap.soat.mecanica.application.saga.contrato.TiposDeMensagem.*;

/**
 * Orquestrador da saga da OS (RFC-002, seção 5). Cada evento recebido avança ou falha um passo;
 * cada avanço grava a OS, a saga e os próximos comandos na outbox, tudo na transação aberta pelo
 * {@link ProcessadorDeEventos} (que também grava a inbox). Nada é publicado daqui: quem publica
 * é o job da outbox, depois do commit.
 *
 * Regras para evento que não casa com o passo atual:
 * - de um passo à frente do atual: chegou fora de ordem, lança {@link EventoForaDeOrdemException}
 *   e a mensagem volta para a fila;
 * - de um passo já passado, ou com a saga encerrada: é atrasado/repetido, registra no log e ignora.
 */
@Slf4j
@Service
public class OrquestradorSaga implements TratadorDeEventos {

    private static final Set<String> TIPOS = Set.of(
            DIAGNOSTICO_ABERTO, DIAGNOSTICO_CONCLUIDO, PECAS_RESERVADAS, RESERVA_PECAS_RECUSADA,
            RESERVA_PECAS_LIBERADA, EXECUCAO_INICIADA, EXECUCAO_FINALIZADA,
            ORCAMENTO_GERADO, ORCAMENTO_APROVADO, ORCAMENTO_RECUSADO, PAGAMENTO_CONFIRMADO, PAGAMENTO_RECUSADO);

    private final SagaOrdemServicoRepository sagaRepository;
    private final OrdemServicoRepository ordemServicoRepository;
    private final VeiculoRepository veiculoRepository;
    private final ServicoRepository servicoRepository;
    private final OutboxRepository outboxRepository;
    private final NotificarAlteracaoSituacaoOrdemServicoUseCase notificarAlteracaoSituacao;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public OrquestradorSaga(SagaOrdemServicoRepository sagaRepository,
                            OrdemServicoRepository ordemServicoRepository,
                            VeiculoRepository veiculoRepository,
                            ServicoRepository servicoRepository,
                            OutboxRepository outboxRepository,
                            NotificarAlteracaoSituacaoOrdemServicoUseCase notificarAlteracaoSituacao,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.sagaRepository = sagaRepository;
        this.ordemServicoRepository = ordemServicoRepository;
        this.veiculoRepository = veiculoRepository;
        this.servicoRepository = servicoRepository;
        this.outboxRepository = outboxRepository;
        this.notificarAlteracaoSituacao = notificarAlteracaoSituacao;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Contexto de quem disparou: vira correlationId/causationId das mensagens geradas. */
    private record Origem(String evento, UUID causationId, String correlationId) {
    }

    // =====================================================================================
    // Passo 1: abertura (chamado pelo CadastrarOrdemServicoUseCase, na transação dele)
    // =====================================================================================

    @Transactional
    public SagaOrdemServico iniciar(OrdemServico os) {
        Veiculo veiculo = veiculoRepository.buscarPorId(os.getVeiculoId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Veículo não encontrado"));

        SagaOrdemServico saga = SagaOrdemServico.iniciar(os.getId(), agora());
        sagaRepository.salvar(saga);

        Origem origem = new Origem("OrdemServicoAberta", null, saga.getSagaId().toString());
        enviar(saga, origem, DestinoMensagem.EXECUCAO, ABRIR_DIAGNOSTICO,
                new Payloads.AbrirDiagnostico(os.getId(), veiculo.getId(), veiculo.getPlaca().getValue()));
        registrarHistorico(saga, origem, null, os.getSituacao());
        return saga;
    }

    // =====================================================================================
    // Eventos
    // =====================================================================================

    @Override
    public Set<String> tipos() {
        return TIPOS;
    }

    @Override
    @Transactional
    public void tratar(EventoRecebido evento) {
        SagaOrdemServico saga = sagaRepository.buscarPorOsIdParaAtualizar(evento.osId())
                .orElseThrow(() -> new IllegalStateException("Saga não encontrada para a OS " + evento.osId()));
        Origem origem = new Origem(evento.type(), evento.messageId(),
                evento.correlationId() != null ? evento.correlationId() : saga.getSagaId().toString());

        switch (evento.type()) {
            case DIAGNOSTICO_ABERTO -> diagnosticoAberto(saga, origem);
            case DIAGNOSTICO_CONCLUIDO -> diagnosticoConcluido(saga, origem,
                    ler(evento, Payloads.DiagnosticoConcluido.class));
            case ORCAMENTO_GERADO -> orcamentoGerado(saga, ler(evento, Payloads.DecisaoOrcamento.class));
            case ORCAMENTO_APROVADO -> orcamentoAprovado(saga, origem, ler(evento, Payloads.DecisaoOrcamento.class));
            case ORCAMENTO_RECUSADO -> orcamentoRecusado(saga, origem);
            case PECAS_RESERVADAS -> pecasReservadas(saga, origem);
            case RESERVA_PECAS_RECUSADA -> reservaPecasRecusada(saga, origem,
                    ler(evento, Payloads.ReservaPecasRecusada.class));
            case RESERVA_PECAS_LIBERADA, EXECUCAO_INICIADA ->
                    log.info("Evento informativo recebido. type={} osId={} passo={} estado={}",
                            evento.type(), saga.getOsId(), saga.getPassoAtual(), saga.getEstado());
            case EXECUCAO_FINALIZADA -> execucaoFinalizada(saga, origem);
            case PAGAMENTO_CONFIRMADO -> pagamentoConfirmado(saga, origem);
            case PAGAMENTO_RECUSADO -> pagamentoRecusado(saga, origem);
            default -> throw new IllegalStateException("Tipo não tratado pelo orquestrador: " + evento.type());
        }

        sagaRepository.salvar(saga);
    }

    // Passo 1 -> 2
    private void diagnosticoAberto(SagaOrdemServico saga, Origem origem) {
        if (!podeTratar(saga, PassoSagaEnum.ABERTURA, origem)) return;

        transicionar(saga, origem, OrdemServico::iniciarDiagnostico);
        saga.avancar(PassoSagaEnum.ABERTURA, PassoSagaEnum.DIAGNOSTICO, agora());
    }

    // Passo 2 -> 3: guarda os itens, pede o orçamento ao Billing
    private void diagnosticoConcluido(SagaOrdemServico saga, Origem origem, Payloads.DiagnosticoConcluido payload) {
        if (!podeTratar(saga, PassoSagaEnum.DIAGNOSTICO, origem)) return;

        saga.registrarItens(payload.itens(), agora());
        OrdemServico os = carregarOs(saga);
        Veiculo veiculo = veiculoRepository.buscarPorId(os.getVeiculoId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Veículo não encontrado"));

        List<Payloads.ItemOrcamento> itens = saga.getItensDiagnostico().stream()
                .map(item -> new Payloads.ItemOrcamento(item.servicoId(), nomeDoServico(item.servicoId()),
                        item.precoMdo(), item.pecas()))
                .toList();
        enviar(saga, origem, DestinoMensagem.BILLING, GERAR_ORCAMENTO,
                new Payloads.GerarOrcamento(saga.getOsId(), veiculo.getClienteId(), itens));

        transicionar(saga, origem, os, OrdemServico::enviarParaAprovacao);
        saga.avancar(PassoSagaEnum.DIAGNOSTICO, PassoSagaEnum.ORCAMENTO, agora());
    }

    // Passo 3 (informativo): o Billing devolve o id do orçamento
    private void orcamentoGerado(SagaOrdemServico saga, Payloads.DecisaoOrcamento payload) {
        if (saga.isEmAndamento()) {
            saga.registrarOrcamento(payload.orcamentoId(), agora());
        }
    }

    // Passo 3 -> 4: aprovado, reserva as peças (ou vai direto à execução se só há mão de obra)
    private void orcamentoAprovado(SagaOrdemServico saga, Origem origem, Payloads.DecisaoOrcamento payload) {
        if (!podeTratar(saga, PassoSagaEnum.ORCAMENTO, origem)) return;

        saga.registrarOrcamento(payload.orcamentoId(), agora());
        saga.avancar(PassoSagaEnum.ORCAMENTO, PassoSagaEnum.RESERVA, agora());

        if (!saga.temPecas()) {
            iniciarExecucao(saga, origem);
            return;
        }

        List<Payloads.PecaQuantidade> pecas = saga.pecasParaReservar().entrySet().stream()
                .map(peca -> new Payloads.PecaQuantidade(peca.getKey(), peca.getValue()))
                .toList();
        UUID ciclo = saga.abrirCicloReserva(agora());
        enviar(saga, origem, DestinoMensagem.EXECUCAO, RESERVAR_PECAS,
                new Payloads.ReservarPecas(saga.getOsId(), ciclo, pecas));
    }

    // Compensação do passo 3 quando o cliente recusa: não há reserva; a OS é cancelada
    private void orcamentoRecusado(SagaOrdemServico saga, Origem origem) {
        if (!podeTratar(saga, PassoSagaEnum.ORCAMENTO, origem)) return;

        transicionar(saga, origem, OrdemServico::cancelarPorDesistencia);
        saga.registrarCompensacao(PassoSagaEnum.DIAGNOSTICO, agora());
        saga.encerrar(EstadoSagaEnum.CANCELADA, "Orçamento recusado pelo cliente", agora());
    }

    // Passo 4 -> 5
    private void pecasReservadas(SagaOrdemServico saga, Origem origem) {
        if (saga.isCancelada() && saga.getCicloReservaId() != null) {
            // A reserva confirmou depois do cancelamento (corrida entre ReservarPecas e
            // LiberarReservaPecas). Libera de novo: o Execução deduplica pelo messageId novo
            // e a liberação do mesmo ciclo é idempotente.
            enviar(saga, origem, DestinoMensagem.EXECUCAO, LIBERAR_RESERVA_PECAS,
                    new Payloads.LiberarReservaPecas(saga.getOsId(), saga.getCicloReservaId()));
            saga.registrarCompensacao(PassoSagaEnum.RESERVA, agora());
            return;
        }
        if (!podeTratar(saga, PassoSagaEnum.RESERVA, origem)) return;

        iniciarExecucao(saga, origem);
    }

    private void iniciarExecucao(SagaOrdemServico saga, Origem origem) {
        enviar(saga, origem, DestinoMensagem.EXECUCAO, INICIAR_EXECUCAO,
                new Payloads.IniciarExecucao(saga.getOsId(), saga.getCicloReservaId()));
        transicionar(saga, origem, OrdemServico::iniciarExecucao);
        saga.avancar(PassoSagaEnum.RESERVA, PassoSagaEnum.EXECUCAO, agora());
    }

    // Falha do passo 4 (sem estoque): cancela o orçamento; a OS continua AGUARDANDO_APROVACAO
    private void reservaPecasRecusada(SagaOrdemServico saga, Origem origem, Payloads.ReservaPecasRecusada payload) {
        if (!podeTratar(saga, PassoSagaEnum.RESERVA, origem)) return;

        enviar(saga, origem, DestinoMensagem.BILLING, CANCELAR_ORCAMENTO,
                new Payloads.ReferenciaOrcamento(saga.getOsId(), saga.getOrcamentoId()));
        saga.registrarCompensacao(PassoSagaEnum.ORCAMENTO, agora());

        // A OS não saiu de AGUARDANDO_APROVACAO durante a reserva; a regra do passo 4 é ela ficar lá.
        OrdemServico os = carregarOs(saga);
        if (!os.isAguardandoAprovacao()) {
            throw new IllegalStateException("OS deveria estar AGUARDANDO_APROVACAO na reserva, está " + os.getSituacao());
        }
        saga.encerrar(EstadoSagaEnum.COMPENSADA, "Peças sem estoque: " + descreverFaltantes(payload), agora());
    }

    // Passo 5 -> 6
    private void execucaoFinalizada(SagaOrdemServico saga, Origem origem) {
        if (!podeTratar(saga, PassoSagaEnum.EXECUCAO, origem)) return;

        transicionar(saga, origem, OrdemServico::finalizar);
        enviar(saga, origem, DestinoMensagem.BILLING, SOLICITAR_PAGAMENTO,
                new Payloads.ReferenciaOrcamento(saga.getOsId(), saga.getOrcamentoId()));
        saga.avancar(PassoSagaEnum.EXECUCAO, PassoSagaEnum.COBRANCA, agora());
    }

    // Passo 6 -> 7: pago, entrega e encerra
    private void pagamentoConfirmado(SagaOrdemServico saga, Origem origem) {
        if (!podeTratar(saga, PassoSagaEnum.COBRANCA, origem)) return;

        transicionar(saga, origem, OrdemServico::entregar);
        saga.avancar(PassoSagaEnum.COBRANCA, PassoSagaEnum.ENTREGA, agora());
        saga.concluir(agora());
    }

    // Falha do passo 6: cancela a cobrança; a OS fica FINALIZADA
    private void pagamentoRecusado(SagaOrdemServico saga, Origem origem) {
        if (!podeTratar(saga, PassoSagaEnum.COBRANCA, origem)) return;

        enviar(saga, origem, DestinoMensagem.BILLING, CANCELAR_COBRANCA,
                new Payloads.ReferenciaOrcamento(saga.getOsId(), saga.getOrcamentoId()));
        saga.registrarCompensacao(PassoSagaEnum.COBRANCA, agora());
        saga.encerrar(EstadoSagaEnum.COMPENSADA, "Pagamento recusado", agora());
    }

    // =====================================================================================
    // Cancelamento pelo cliente (PATCH /ordem-servicos/{id}/cancelar): compensa em ordem inversa
    // =====================================================================================

    /**
     * Desfaz os passos já feitos, do mais recente para o mais antigo. No passo 5 (execução em
     * andamento) isso gera as duas compensações em serviços diferentes do vídeo:
     * LiberarReservaPecas (Execução) e CancelarOrcamento (Billing).
     */
    @Transactional
    public OrdemServico cancelarPorDesistencia(OrdemServico os) {
        SituacaoOrdemServicoEnum anterior = os.getSituacao();
        os.cancelarPorDesistencia(); // valida antes de qualquer compensação (FINALIZADA/ENTREGUE lançam)
        if (os.getSituacao() == anterior) {
            return os;
        }

        SagaOrdemServico saga = sagaRepository.buscarPorOsIdParaAtualizar(os.getId()).orElse(null);
        OrdemServico salva = ordemServicoRepository.salvar(os);
        notificarAlteracaoSituacao.executar(salva, anterior);

        if (saga == null) {
            return salva; // OS anterior à saga: só muda a situação
        }

        Origem origem = new Origem("CancelamentoPorDesistencia", null, saga.getSagaId().toString());
        registrarHistorico(saga, origem, anterior, salva.getSituacao());

        if (saga.isEmAndamento()) {
            compensarEmOrdemInversa(saga, origem);
        }
        saga.registrarCompensacao(PassoSagaEnum.DIAGNOSTICO, agora());
        saga.encerrar(EstadoSagaEnum.CANCELADA, "Cancelada por desistência do cliente", agora());
        sagaRepository.salvar(saga);
        return salva;
    }

    private void compensarEmOrdemInversa(SagaOrdemServico saga, Origem origem) {
        PassoSagaEnum passo = saga.getPassoAtual();

        // Reserva: feita (EXECUCAO) ou em voo (RESERVA). ReservarPecas pode já ter sido processado.
        boolean reservaPodeExistir = passo == PassoSagaEnum.RESERVA || passo == PassoSagaEnum.EXECUCAO;
        if (reservaPodeExistir && saga.temPecas()) {
            enviar(saga, origem, DestinoMensagem.EXECUCAO, LIBERAR_RESERVA_PECAS,
                    new Payloads.LiberarReservaPecas(saga.getOsId(), saga.getCicloReservaId()));
            saga.registrarCompensacao(PassoSagaEnum.RESERVA, agora());
        }

        // Orçamento: pedido a partir do passo 3.
        if (passo.ordinal() >= PassoSagaEnum.ORCAMENTO.ordinal()) {
            enviar(saga, origem, DestinoMensagem.BILLING, CANCELAR_ORCAMENTO,
                    new Payloads.ReferenciaOrcamento(saga.getOsId(), saga.getOrcamentoId()));
            saga.registrarCompensacao(PassoSagaEnum.ORCAMENTO, agora());
        }
    }

    // =====================================================================================
    // Apoio
    // =====================================================================================

    private boolean podeTratar(SagaOrdemServico saga, PassoSagaEnum esperado, Origem origem) {
        if (saga.estaNoPasso(esperado)) {
            return true;
        }
        if (saga.aindaNaoChegouEm(esperado)) {
            throw new EventoForaDeOrdemException("Evento " + origem.evento() + " do passo " + esperado
                    + " chegou com a saga no passo " + saga.getPassoAtual());
        }
        log.warn("Evento ignorado: não casa com a saga. type={} osId={} passoEsperado={} passoAtual={} estado={}",
                origem.evento(), saga.getOsId(), esperado, saga.getPassoAtual(), saga.getEstado());
        return false;
    }

    private void transicionar(SagaOrdemServico saga, Origem origem, Consumer<OrdemServico> transicao) {
        transicionar(saga, origem, carregarOs(saga), transicao);
    }

    private void transicionar(SagaOrdemServico saga, Origem origem, OrdemServico os,
                              Consumer<OrdemServico> transicao) {
        SituacaoOrdemServicoEnum anterior = os.getSituacao();
        transicao.accept(os);
        if (anterior == os.getSituacao()) {
            return;
        }
        OrdemServico salva = ordemServicoRepository.salvar(os);
        notificarAlteracaoSituacao.executar(salva, anterior);
        registrarHistorico(saga, origem, anterior, salva.getSituacao());
    }

    private void registrarHistorico(SagaOrdemServico saga, Origem origem,
                                    SituacaoOrdemServicoEnum anterior, SituacaoOrdemServicoEnum nova) {
        enviar(saga, origem, DestinoMensagem.HISTORICO, SITUACAO_ALTERADA,
                new Payloads.SituacaoAlterada(saga.getOsId(), anterior == null ? null : anterior.name(),
                        nova.name(), origem.evento(), agora()));
    }

    private void enviar(SagaOrdemServico saga, Origem origem, DestinoMensagem destino, String tipo, Object payload) {
        outboxRepository.registrar(new MensagemSaida(destino, tipo, saga.getSagaId(), saga.getOsId(),
                origem.correlationId(), origem.causationId(), payload));
    }

    private OrdemServico carregarOs(SagaOrdemServico saga) {
        return ordemServicoRepository.buscarPorId(saga.getOsId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de serviço não encontrada"));
    }

    private String nomeDoServico(UUID servicoId) {
        return servicoRepository.buscarPorId(servicoId)
                .map(Servico::getNome)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Serviço " + servicoId + " não encontrado"));
    }

    private static String descreverFaltantes(Payloads.ReservaPecasRecusada payload) {
        if (payload.faltantes() == null || payload.faltantes().isEmpty()) {
            return "não informadas";
        }
        return payload.faltantes().stream()
                .map(peca -> peca.pecaId() + " x" + peca.qtd())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private <T> T ler(EventoRecebido evento, Class<T> tipo) {
        try {
            return objectMapper.treeToValue(evento.payload(), tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload inválido para " + evento.type(), e);
        }
    }

    private Instant agora() {
        return Instant.now(clock);
    }

    /** Usado pelo GET da saga. */
    @Transactional(readOnly = true)
    public SagaOrdemServico buscarPorOsId(UUID osId) {
        return sagaRepository.buscarPorOsId(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Saga não encontrada para a OS"));
    }
}
