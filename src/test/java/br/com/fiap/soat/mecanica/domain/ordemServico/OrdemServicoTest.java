package br.com.fiap.soat.mecanica.domain.ordemServico;

import br.com.fiap.soat.mecanica.domain.enums.SituacaoOrdemServicoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import br.com.fiap.soat.mecanica.util.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrdemServicoTest {

    @Nested
    @DisplayName("Construtor")
    class Construtor {

        @Test
        @DisplayName("Deve criar OrdemServico com situação RECEBIDA")
        void deveCriar_quandoDadosValidos() {
            // Arrange
            UUID veiculoId = UUID.randomUUID();
            UUID usuarioId = UUID.randomUUID();

            // Act
            OrdemServico os = new OrdemServico("Obs", veiculoId, usuarioId);

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.RECEBIDA);
            assertThat(os.getDataRecebida()).isNotNull();
            assertThat(os.isAtivo()).isTrue();
        }

        @Test
        @DisplayName("Deve lançar exceção quando veiculoId é nulo")
        void deveLancarExcecao_quandoVeiculoIdNulo() {
            assertThatThrownBy(() -> new OrdemServico("Obs", null, UUID.randomUUID()))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("veículo");
        }

        @Test
        @DisplayName("Deve lançar exceção quando usuarioId é nulo")
        void deveLancarExcecao_quandoUsuarioIdNulo() {
            assertThatThrownBy(() -> new OrdemServico("Obs", UUID.randomUUID(), null))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("mecânico");
        }
    }

    @Nested
    @DisplayName("Reconstruir")
    class Reconstruir {

        @Test
        @DisplayName("Deve reconstruir OrdemServico com todos os campos")
        void deveReconstruir_quandoDadosCompletos() {
            // Act
            OrdemServico os = TestDataFactory.criarOrdemServicoEmExecucao();

            // Assert
            assertThat(os.getId()).isNotNull();
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.EM_EXECUCAO);
        }
    }

    @Nested
    @DisplayName("Transições de estado")
    class TransicoesDeEstado {

        @Test
        @DisplayName("Deve iniciar diagnóstico quando RECEBIDA")
        void deveIniciarDiagnostico_quandoRecebida() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();

            // Act
            os.iniciarDiagnostico();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.EM_DIAGNOSTICO);
            assertThat(os.getDataDiagnostico()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar exceção ao iniciar diagnóstico quando não RECEBIDA")
        void deveLancarExcecao_quandoIniciarDiagnosticoNaoRecebida() {
            OrdemServico os = TestDataFactory.criarOrdemServicoEmDiagnostico();
            assertThatThrownBy(os::iniciarDiagnostico)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve enviar para aprovação quando EM_DIAGNOSTICO")
        void deveEnviarParaAprovacao_quandoEmDiagnostico() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoEmDiagnostico();

            // Act
            os.enviarParaAprovacao();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.AGUARDANDO_APROVACAO);
            assertThat(os.getDataAguardandoAprovacao()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar exceção ao enviar para aprovação quando não EM_DIAGNOSTICO")
        void deveLancarExcecao_quandoEnviarAprovacaoNaoEmDiagnostico() {
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
            assertThatThrownBy(os::enviarParaAprovacao)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve iniciar execução quando AGUARDANDO_APROVACAO")
        void deveIniciarExecucao_quandoAguardandoAprovacao() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();

            // Act
            os.iniciarExecucao();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.EM_EXECUCAO);
            assertThat(os.getDataExecucao()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar exceção ao iniciar execução quando não AGUARDANDO_APROVACAO")
        void deveLancarExcecao_quandoIniciarExecucaoNaoAguardando() {
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
            assertThatThrownBy(os::iniciarExecucao)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve finalizar quando EM_EXECUCAO")
        void deveFinalizar_quandoEmExecucao() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoEmExecucao();

            // Act
            os.finalizar();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.FINALIZADA);
            assertThat(os.getDataFinalizada()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar exceção ao finalizar quando não EM_EXECUCAO")
        void deveLancarExcecao_quandoFinalizarNaoEmExecucao() {
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
            assertThatThrownBy(os::finalizar)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve entregar quando FINALIZADA")
        void deveEntregar_quandoFinalizada() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoFinalizada();

            // Act
            os.entregar();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.ENTREGUE);
            assertThat(os.getDataEntregue()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar exceção ao entregar quando não FINALIZADA")
        void deveLancarExcecao_quandoEntregarNaoFinalizada() {
            OrdemServico os = TestDataFactory.criarOrdemServicoEmExecucao();
            assertThatThrownBy(os::entregar)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve voltar para diagnóstico quando AGUARDANDO_APROVACAO")
        void deveVoltarParaDiagnostico_quandoAguardandoAprovacao() {
            // Arrange
            OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();

            // Act
            os.voltarParaDiagnostico();

            // Assert
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.EM_DIAGNOSTICO);
        }

        @Test
        @DisplayName("Deve lançar exceção ao voltar diagnóstico quando não AGUARDANDO_APROVACAO")
        void deveLancarExcecao_quandoVoltarDiagnosticoNaoAguardando() {
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
            assertThatThrownBy(os::voltarParaDiagnostico)
                    .isInstanceOf(RegraNegocioException.class);
        }

        @ParameterizedTest(name = "Deve cancelar quando {0}")
        @MethodSource("br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoTest#osCancelaveis")
        void deveCancelar_semInativarRegistro(String situacao, OrdemServico os) {
            os.cancelarPorDesistencia();

            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
            assertThat(os.isAtivo()).isTrue();
        }

        @Test
        @DisplayName("Deve ignorar novo cancelamento quando já CANCELADA (mensagem repetida da saga)")
        void deveIgnorar_quandoCancelarDuasVezes() {
            OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
            os.cancelarPorDesistencia();

            os.cancelarPorDesistencia();

            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
        }

        @Test
        @DisplayName("Deve lançar exceção ao cancelar quando FINALIZADA")
        void deveLancarExcecao_quandoCancelarFinalizada() {
            OrdemServico os = TestDataFactory.criarOrdemServicoFinalizada();

            assertThatThrownBy(os::cancelarPorDesistencia).isInstanceOf(RegraNegocioException.class);
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.FINALIZADA);
        }

        @Test
        @DisplayName("Deve lançar exceção ao cancelar quando ENTREGUE")
        void deveLancarExcecao_quandoCancelarEntregue() {
            OrdemServico os = TestDataFactory.criarOrdemServicoEntregue();

            assertThatThrownBy(os::cancelarPorDesistencia).isInstanceOf(RegraNegocioException.class);
            assertThat(os.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.ENTREGUE);
        }

        @Test
        @DisplayName("Deve bloquear as demais transições quando CANCELADA")
        void deveBloquearTransicoes_quandoCancelada() {
            OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
            os.cancelarPorDesistencia();

            assertThatThrownBy(os::iniciarDiagnostico).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::enviarParaAprovacao).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::voltarParaDiagnostico).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::iniciarExecucao).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::finalizar).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::entregar).isInstanceOf(RegraNegocioException.class);
        }

        @Test
        @DisplayName("Deve lançar exceção em qualquer transição quando OS inativa")
        void deveLancarExcecao_quandoOsInativa() {
            OrdemServico os = TestDataFactory.criarOrdemServicoInativa();

            assertThatThrownBy(os::iniciarDiagnostico).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::enviarParaAprovacao).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::iniciarExecucao).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::finalizar).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::entregar).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(os::cancelarPorDesistencia).isInstanceOf(RegraNegocioException.class);
        }
    }

    static Stream<Arguments> osCancelaveis() {
        return Stream.of(
                Arguments.of("RECEBIDA", TestDataFactory.criarOrdemServicoRecebida()),
                Arguments.of("EM_DIAGNOSTICO", TestDataFactory.criarOrdemServicoEmDiagnostico()),
                Arguments.of("AGUARDANDO_APROVACAO", TestDataFactory.criarOrdemServicoAguardandoAprovacao()),
                Arguments.of("EM_EXECUCAO", TestDataFactory.criarOrdemServicoEmExecucao())
        );
    }

    @Nested
    @DisplayName("Validações de diagnóstico")
    class ValidacoesDiagnostico {

        @Test
        @DisplayName("Deve validar que permite alterar diagnóstico")
        void deveValidar_quandoEmDiagnostico() {
            OrdemServico os = TestDataFactory.criarOrdemServicoEmDiagnostico();
            os.validarPermiteAlterarDiagnostico();
            assertThat(os.isEmDiagnostico()).isTrue();
        }

        @Test
        @DisplayName("Deve lançar exceção quando não está em diagnóstico")
        void deveLancarExcecao_quandoNaoEmDiagnostico() {
            OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
            assertThatThrownBy(os::validarPermiteAlterarDiagnostico)
                    .isInstanceOf(RegraNegocioException.class);
        }
    }

    @Nested
    @DisplayName("Status checks")
    class StatusChecks {

        @Test
        @DisplayName("Deve retornar true para isAguardandoAprovacao")
        void deveRetornarTrue_quandoAguardandoAprovacao() {
            OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
            assertThat(os.isAguardandoAprovacao()).isTrue();
        }

        @Test
        @DisplayName("Deve retornar true para isEmExecucao")
        void deveRetornarTrue_quandoEmExecucao() {
            OrdemServico os = TestDataFactory.criarOrdemServicoEmExecucao();
            assertThat(os.isEmExecucao()).isTrue();
        }

        @Test
        @DisplayName("Deve retornar true para isFinalizada")
        void deveRetornarTrue_quandoFinalizada() {
            OrdemServico os = TestDataFactory.criarOrdemServicoFinalizada();
            assertThat(os.isFinalizada()).isTrue();
        }
    }
}
