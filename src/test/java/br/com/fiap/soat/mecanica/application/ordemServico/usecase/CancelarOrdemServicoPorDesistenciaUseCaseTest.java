package br.com.fiap.soat.mecanica.application.ordemServico.usecase;

import br.com.fiap.soat.mecanica.domain.enums.SituacaoOrdemServicoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import br.com.fiap.soat.mecanica.util.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelarOrdemServicoPorDesistenciaUseCaseTest {

    @Mock
    private OrdemServicoRepository ordemServicoRepository;
    @Mock
    private NotificarAlteracaoSituacaoOrdemServicoUseCase notificacaoUseCase;
    @InjectMocks
    private CancelarOrdemServicoPorDesistenciaUseCase useCase;

    @Test
    @DisplayName("Deve cancelar OS por desistência com sucesso")
    void deveCancelar_quandoOsAguardandoAprovacao() {
        // Arrange
        OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));
        when(ordemServicoRepository.salvar(any())).thenReturn(os);

        // Act
        OrdemServico resultado = useCase.executar(os.getId());

        // Assert
        assertThat(resultado.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
        verify(ordemServicoRepository).salvar(os);
        verify(notificacaoUseCase).executar(os, SituacaoOrdemServicoEnum.AGUARDANDO_APROVACAO);
    }

    @Test
    @DisplayName("Deve lançar exceção quando OS não encontrada")
    void deveLancarExcecao_quandoNaoEncontrada() {
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.executar(UUID.randomUUID()))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Nao deve salvar nem notificar ao cancelar OS ja CANCELADA")
    void naoDeveSalvarNemNotificar_quandoJaCancelada() {
        OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
        os.cancelarPorDesistencia();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));

        OrdemServico resultado = useCase.executar(os.getId());

        assertThat(resultado.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
        verify(ordemServicoRepository, never()).salvar(any());
        verifyNoInteractions(notificacaoUseCase);
    }

    @Test
    @DisplayName("Nao deve salvar nem notificar ao cancelar OS ja FINALIZADA")
    void naoDeveSalvarNemNotificar_quandoJaFinalizada() {
        OrdemServico os = TestDataFactory.criarOrdemServicoFinalizada();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> useCase.executar(os.getId()))
                .isInstanceOf(RegraNegocioException.class);

        verify(ordemServicoRepository, never()).salvar(any());
        verifyNoInteractions(notificacaoUseCase);
    }

    @Test
    @DisplayName("Nao deve salvar nem notificar ao cancelar OS ja ENTREGUE")
    void naoDeveSalvarNemNotificar_quandoJaEntregue() {
        OrdemServico os = TestDataFactory.criarOrdemServicoEntregue();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> useCase.executar(os.getId()))
                .isInstanceOf(RegraNegocioException.class);

        verify(ordemServicoRepository, never()).salvar(any());
        verifyNoInteractions(notificacaoUseCase);
    }
}
