package br.com.fiap.soat.mecanica.domain.saga;

import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaOrdemServicoTest {

    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-17T10:05:00Z");

    @Test
    @DisplayName("Abrir a OS cria a saga no passo 1, em andamento")
    void deveIniciarNoPassoUm() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);

        assertThat(saga.getSagaId()).isNotNull();
        assertThat(saga.getPassoAtual()).isEqualTo(PassoSagaEnum.ABERTURA);
        assertThat(saga.getEstado()).isEqualTo(EstadoSagaEnum.EM_ANDAMENTO);
        assertThat(saga.getPassosConcluidos()).isEmpty();
        assertThat(saga.getCriadoEm()).isEqualTo(T0);
        assertThatThrownBy(() -> SagaOrdemServico.iniciar(null, T0)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Avança só a partir do passo atual e só para frente")
    void deveAvancar() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);

        saga.avancar(PassoSagaEnum.ABERTURA, PassoSagaEnum.DIAGNOSTICO, T1);

        assertThat(saga.getPassoAtual()).isEqualTo(PassoSagaEnum.DIAGNOSTICO);
        assertThat(saga.getPassosConcluidos()).containsExactly(PassoSagaEnum.ABERTURA);
        assertThat(saga.getAtualizadoEm()).isEqualTo(T1);
        assertThatThrownBy(() -> saga.avancar(PassoSagaEnum.ABERTURA, PassoSagaEnum.DIAGNOSTICO, T1))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> saga.avancar(PassoSagaEnum.DIAGNOSTICO, PassoSagaEnum.ABERTURA, T1))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Sabe se um evento chegou adiantado")
    void deveDetectarEventoAdiantado() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);

        assertThat(saga.aindaNaoChegouEm(PassoSagaEnum.DIAGNOSTICO)).isTrue();
        assertThat(saga.aindaNaoChegouEm(PassoSagaEnum.ABERTURA)).isFalse();
        assertThat(saga.estaNoPasso(PassoSagaEnum.ABERTURA)).isTrue();
    }

    @Test
    @DisplayName("Soma as peças por pecaId (o Execução recusa pecaId repetido)")
    void deveSomarPecasRepetidas() {
        UUID filtro = UUID.randomUUID();
        UUID oleo = UUID.randomUUID();
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);
        saga.registrarItens(List.of(
                new ItemDiagnostico(UUID.randomUUID(), BigDecimal.TEN, List.of(
                        new ItemDiagnostico.PecaDiagnostico(filtro, 1, BigDecimal.ONE),
                        new ItemDiagnostico.PecaDiagnostico(oleo, 4, BigDecimal.ONE))),
                new ItemDiagnostico(UUID.randomUUID(), BigDecimal.TEN, List.of(
                        new ItemDiagnostico.PecaDiagnostico(filtro, 2, BigDecimal.ONE)))), T1);

        assertThat(saga.pecasParaReservar()).containsEntry(filtro, 3).containsEntry(oleo, 4).hasSize(2);
        assertThat(saga.temPecas()).isTrue();
    }

    @Test
    @DisplayName("Item só de mão de obra não tem peça a reservar")
    void semPecas() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);
        saga.registrarItens(List.of(new ItemDiagnostico(UUID.randomUUID(), BigDecimal.TEN, null)), T1);

        assertThat(saga.temPecas()).isFalse();
    }

    @Test
    @DisplayName("Conclui só na entrega")
    void deveConcluir() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);
        assertThatThrownBy(() -> saga.concluir(T1)).isInstanceOf(RegraNegocioException.class);

        saga.avancar(PassoSagaEnum.ABERTURA, PassoSagaEnum.ENTREGA, T1);
        saga.concluir(T1);

        assertThat(saga.getEstado()).isEqualTo(EstadoSagaEnum.CONCLUIDA);
        assertThat(saga.getPassosConcluidos()).endsWith(PassoSagaEnum.ENTREGA);
    }

    @Test
    @DisplayName("Registra compensações sem repetir e encerra só como CANCELADA ou COMPENSADA")
    void deveCompensarEEncerrar() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);

        saga.registrarCompensacao(PassoSagaEnum.RESERVA, T1);
        saga.registrarCompensacao(PassoSagaEnum.RESERVA, T1);
        saga.registrarCompensacao(PassoSagaEnum.ORCAMENTO, T1);
        saga.encerrar(EstadoSagaEnum.CANCELADA, "desistência", T1);

        assertThat(saga.getPassosCompensados()).containsExactly(PassoSagaEnum.RESERVA, PassoSagaEnum.ORCAMENTO);
        assertThat(saga.isCancelada()).isTrue();
        assertThat(saga.isEmAndamento()).isFalse();
        assertThat(saga.getMotivo()).isEqualTo("desistência");
        assertThatThrownBy(() -> saga.encerrar(EstadoSagaEnum.CONCLUIDA, "x", T1))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Guarda o orçamento e abre um ciclo de reserva novo a cada tentativa")
    void deveGuardarOrcamentoECiclo() {
        SagaOrdemServico saga = SagaOrdemServico.iniciar(UUID.randomUUID(), T0);
        UUID orcamento = UUID.randomUUID();

        saga.registrarOrcamento(null, T1);
        saga.registrarOrcamento(orcamento, T1);
        UUID ciclo1 = saga.abrirCicloReserva(T1);
        UUID ciclo2 = saga.abrirCicloReserva(T1);

        assertThat(saga.getOrcamentoId()).isEqualTo(orcamento);
        assertThat(ciclo1).isNotEqualTo(ciclo2);
        assertThat(saga.getCicloReservaId()).isEqualTo(ciclo2);
    }
}
