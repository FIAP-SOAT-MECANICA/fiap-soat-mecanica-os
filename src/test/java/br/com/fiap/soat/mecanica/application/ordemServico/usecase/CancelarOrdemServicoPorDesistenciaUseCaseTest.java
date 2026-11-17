package br.com.fiap.soat.mecanica.application.ordemServico.usecase;

import br.com.fiap.soat.mecanica.application.saga.OrquestradorSaga;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelarOrdemServicoPorDesistenciaUseCaseTest {

    @Mock
    private OrdemServicoRepository ordemServicoRepository;
    @Mock
    private OrquestradorSaga orquestradorSaga;
    @InjectMocks
    private CancelarOrdemServicoPorDesistenciaUseCase useCase;

    @Test
    @DisplayName("Deve delegar o cancelamento ao orquestrador, que compensa a saga")
    void deveDelegarAoOrquestrador() {
        OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));
        when(orquestradorSaga.cancelarPorDesistencia(os)).thenAnswer(inv -> {
            os.cancelarPorDesistencia();
            return os;
        });

        OrdemServico resultado = useCase.executar(os.getId());

        assertThat(resultado.getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
        verify(orquestradorSaga).cancelarPorDesistencia(os);
    }

    @Test
    @DisplayName("Deve devolver a OS já cancelada sem erro")
    void deveSerIdempotente() {
        OrdemServico os = TestDataFactory.criarOrdemServicoAguardandoAprovacao();
        os.cancelarPorDesistencia();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));
        when(orquestradorSaga.cancelarPorDesistencia(os)).thenReturn(os);

        assertThat(useCase.executar(os.getId()).getSituacao()).isEqualTo(SituacaoOrdemServicoEnum.CANCELADA);
    }

    @Test
    @DisplayName("Deve propagar a recusa do domínio (OS finalizada não cancela)")
    void devePropagarRegraDeNegocio() {
        OrdemServico os = TestDataFactory.criarOrdemServicoFinalizada();
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.of(os));
        when(orquestradorSaga.cancelarPorDesistencia(os)).thenThrow(new RegraNegocioException("finalizada"));

        assertThatThrownBy(() -> useCase.executar(os.getId())).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Deve lançar exceção quando OS não encontrada")
    void deveLancarExcecao_quandoNaoEncontrada() {
        when(ordemServicoRepository.buscarPorId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.executar(UUID.randomUUID()))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(orquestradorSaga);
    }
}
