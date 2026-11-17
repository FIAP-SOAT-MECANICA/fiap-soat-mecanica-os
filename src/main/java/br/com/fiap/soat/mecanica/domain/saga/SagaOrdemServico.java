package br.com.fiap.soat.mecanica.domain.saga;

import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instância da saga de uma OS. Guarda em que passo ela está, o que já foi concluído e o que já
 * foi compensado. As decisões de "qual comando mandar" ficam no orquestrador; aqui ficam as
 * regras de estado que não podem ser violadas.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SagaOrdemServico {

    private UUID sagaId;
    private UUID osId;
    private PassoSagaEnum passoAtual;
    private EstadoSagaEnum estado;
    private List<PassoSagaEnum> passosConcluidos = new ArrayList<>();
    private List<PassoSagaEnum> passosCompensados = new ArrayList<>();
    private List<ItemDiagnostico> itensDiagnostico = new ArrayList<>();
    private UUID orcamentoId;
    private UUID cicloReservaId;
    private String motivo;
    private Instant criadoEm;
    private Instant atualizadoEm;

    public static SagaOrdemServico iniciar(UUID osId, Instant agora) {
        if (osId == null) {
            throw new RegraNegocioException("A saga precisa de uma OS");
        }
        SagaOrdemServico saga = new SagaOrdemServico();
        saga.sagaId = UUID.randomUUID();
        saga.osId = osId;
        saga.passoAtual = PassoSagaEnum.ABERTURA;
        saga.estado = EstadoSagaEnum.EM_ANDAMENTO;
        saga.criadoEm = agora;
        saga.atualizadoEm = agora;
        return saga;
    }

    @SuppressWarnings("java:S107")
    public static SagaOrdemServico reconstruir(UUID sagaId, UUID osId, PassoSagaEnum passoAtual, EstadoSagaEnum estado,
                                               List<PassoSagaEnum> passosConcluidos,
                                               List<PassoSagaEnum> passosCompensados,
                                               List<ItemDiagnostico> itensDiagnostico, UUID orcamentoId,
                                               UUID cicloReservaId, String motivo, Instant criadoEm,
                                               Instant atualizadoEm) {
        SagaOrdemServico saga = new SagaOrdemServico();
        saga.sagaId = sagaId;
        saga.osId = osId;
        saga.passoAtual = passoAtual;
        saga.estado = estado;
        saga.passosConcluidos = new ArrayList<>(passosConcluidos);
        saga.passosCompensados = new ArrayList<>(passosCompensados);
        saga.itensDiagnostico = new ArrayList<>(itensDiagnostico == null ? List.of() : itensDiagnostico);
        saga.orcamentoId = orcamentoId;
        saga.cicloReservaId = cicloReservaId;
        saga.motivo = motivo;
        saga.criadoEm = criadoEm;
        saga.atualizadoEm = atualizadoEm;
        return saga;
    }

    public boolean isEmAndamento() {
        return estado == EstadoSagaEnum.EM_ANDAMENTO;
    }

    public boolean isCancelada() {
        return estado == EstadoSagaEnum.CANCELADA;
    }

    public boolean estaNoPasso(PassoSagaEnum passo) {
        return isEmAndamento() && passoAtual == passo;
    }

    /** O evento esperado num passo que a saga ainda não alcançou: chegou fora de ordem. */
    public boolean aindaNaoChegouEm(PassoSagaEnum passo) {
        return isEmAndamento() && passoAtual.ordinal() < passo.ordinal();
    }

    public void avancar(PassoSagaEnum concluido, PassoSagaEnum proximo, Instant agora) {
        if (!estaNoPasso(concluido)) {
            throw new RegraNegocioException("Saga não está no passo " + concluido + " (passo atual " + passoAtual
                    + ", estado " + estado + ")");
        }
        if (proximo.ordinal() <= concluido.ordinal()) {
            throw new RegraNegocioException("A saga só avança para frente");
        }
        passosConcluidos.add(concluido);
        passoAtual = proximo;
        atualizadoEm = agora;
    }

    public void concluir(Instant agora) {
        if (!estaNoPasso(PassoSagaEnum.ENTREGA)) {
            throw new RegraNegocioException("Saga só conclui na entrega");
        }
        passosConcluidos.add(PassoSagaEnum.ENTREGA);
        estado = EstadoSagaEnum.CONCLUIDA;
        atualizadoEm = agora;
    }

    public void registrarCompensacao(PassoSagaEnum passo, Instant agora) {
        if (!passosCompensados.contains(passo)) {
            passosCompensados.add(passo);
        }
        atualizadoEm = agora;
    }

    public void encerrar(EstadoSagaEnum estadoFinal, String motivo, Instant agora) {
        if (estadoFinal != EstadoSagaEnum.CANCELADA && estadoFinal != EstadoSagaEnum.COMPENSADA) {
            throw new RegraNegocioException("Encerramento só por cancelamento ou compensação");
        }
        this.estado = estadoFinal;
        this.motivo = motivo;
        this.atualizadoEm = agora;
    }

    public void registrarItens(List<ItemDiagnostico> itens, Instant agora) {
        this.itensDiagnostico = new ArrayList<>(itens == null ? List.of() : itens);
        this.atualizadoEm = agora;
    }

    public void registrarOrcamento(UUID orcamentoId, Instant agora) {
        if (orcamentoId != null) {
            this.orcamentoId = orcamentoId;
            this.atualizadoEm = agora;
        }
    }

    /** Novo ciclo de reserva (contrato do Execução: um cycleId por tentativa de reserva). */
    public UUID abrirCicloReserva(Instant agora) {
        this.cicloReservaId = UUID.randomUUID();
        this.atualizadoEm = agora;
        return cicloReservaId;
    }

    /** Peças a reservar, somadas por pecaId: o Execução recusa pecaId repetido na lista. */
    public Map<UUID, Integer> pecasParaReservar() {
        Map<UUID, Integer> pecas = new LinkedHashMap<>();
        itensDiagnostico.forEach(item -> item.pecas().forEach(
                peca -> pecas.merge(peca.pecaId(), peca.qtd(), Integer::sum)));
        return pecas;
    }

    public boolean temPecas() {
        return !pecasParaReservar().isEmpty();
    }

    public List<PassoSagaEnum> getPassosConcluidos() {
        return Collections.unmodifiableList(passosConcluidos);
    }

    public List<PassoSagaEnum> getPassosCompensados() {
        return Collections.unmodifiableList(passosCompensados);
    }

    public List<ItemDiagnostico> getItensDiagnostico() {
        return Collections.unmodifiableList(itensDiagnostico);
    }
}
