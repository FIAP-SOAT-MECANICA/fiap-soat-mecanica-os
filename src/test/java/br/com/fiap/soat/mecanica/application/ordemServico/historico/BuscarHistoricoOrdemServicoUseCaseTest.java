package br.com.fiap.soat.mecanica.application.ordemServico.historico;

import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import br.com.fiap.soat.mecanica.util.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuscarHistoricoOrdemServicoUseCaseTest {

    @Mock
    private OrdemServicoRepository ordemServicoRepository;
    @Mock
    private HistoricoOrdemServicoRepository historicoRepository;
    @InjectMocks
    private BuscarHistoricoOrdemServicoUseCase useCase;

    @Test
    @DisplayName("Devolve o histórico da OS existente")
    void deveDevolverHistorico() {
        UUID osId = UUID.randomUUID();
        List<EntradaHistorico> historico = List.of(new EntradaHistorico(osId, Instant.now(), UUID.randomUUID(),
                null, "RECEBIDA", "OrdemServicoAberta"));
        when(ordemServicoRepository.buscarPorId(osId))
                .thenReturn(Optional.of(TestDataFactory.criarOrdemServicoRecebida()));
        when(historicoRepository.listarPorOsId(osId)).thenReturn(historico);

        assertThat(useCase.executar(osId)).isEqualTo(historico);
    }

    @Test
    @DisplayName("404 quando a OS não existe, sem consultar o DynamoDB")
    void deveFalharSemOs() {
        UUID osId = UUID.randomUUID();
        when(ordemServicoRepository.buscarPorId(osId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.executar(osId)).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(historicoRepository);
    }
}
