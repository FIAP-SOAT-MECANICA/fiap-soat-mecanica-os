package br.com.fiap.soat.mecanica.application.servico;

import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.servico.Servico;
import br.com.fiap.soat.mecanica.domain.servico.ServicoRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuscarServicoPorIdUseCaseTest {

    @Mock
    private ServicoRepository servicoRepository;
    @InjectMocks
    private BuscarServicoPorIdUseCase buscarServicoPorIdUseCase;

    @Test
    @DisplayName("Deve buscar Serviço por ID com sucesso")
    void deveBuscar_quandoServicoExiste() {
        //Arrange
        Servico servico = TestDataFactory.criarServicoValido();
        when(servicoRepository.buscarPorId(any())).thenReturn(Optional.of(servico));

        //Act
        Servico resultado = buscarServicoPorIdUseCase.executar(servico.getId());

        //Assert
        assertThat(resultado).isEqualTo(servico);
    }

    @Test
    @DisplayName("Deve lançar exceção quando serviço não encontrado")
    void deveLancarExcecao_quandoNaoEncontrado() {
        when(servicoRepository.buscarPorId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> buscarServicoPorIdUseCase.executar(UUID.randomUUID()))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }
}
