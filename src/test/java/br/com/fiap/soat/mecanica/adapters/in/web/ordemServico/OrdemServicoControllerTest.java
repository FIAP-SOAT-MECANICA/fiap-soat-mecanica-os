package br.com.fiap.soat.mecanica.adapters.in.web.ordemServico;

import br.com.fiap.soat.mecanica.adapters.in.web.security.CustomUserDetailsService;
import br.com.fiap.soat.mecanica.adapters.out.security.JwtService;
import br.com.fiap.soat.mecanica.application.ordemServico.dto.Pagina;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.BuscarHistoricoOrdemServicoUseCase;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.saga.OrquestradorSaga;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServico;
import java.time.Instant;
import br.com.fiap.soat.mecanica.application.ordemServico.usecase.*;
import br.com.fiap.soat.mecanica.config.SecurityConfig;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.util.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = OrdemServicoController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(SecurityConfig.class)
class OrdemServicoControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CadastrarOrdemServicoUseCase cadastrarUseCase;
    @MockitoBean
    private BuscarOrdemServicoPorIdUseCase buscarPorIdUseCase;
    @MockitoBean
    private CancelarOrdemServicoPorDesistenciaUseCase cancelarUseCase;
    @MockitoBean
    private BuscarTodosOrdemServicoPorVeiculoPlacaUseCase buscarTodosPorPlacaUseCase;
    @MockitoBean
    private ListarOrdensServicoAtivasUseCase listarOrdensServicoAtivasUseCase;
    @MockitoBean
    private BuscarHistoricoOrdemServicoUseCase buscarHistoricoUseCase;
    @MockitoBean
    private OrquestradorSaga orquestradorSaga;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve cadastrar OS com role MECANICO")
    void deveCadastrar_quandoMecanico() throws Exception {
        OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
        when(cadastrarUseCase.executar(anyString(), any())).thenReturn(os);

        String json = """
                {"observacao":"Teste","veiculoId":"%s"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/ordem-servicos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve buscar OS por ID")
    void deveBuscarPorId() throws Exception {
        OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
        when(buscarPorIdUseCase.executar(any())).thenReturn(os);

        mockMvc.perform(get("/ordem-servicos/{id}", os.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve buscar todas OS por placa do veículo")
    void deveBuscarTodosPorVeiculoPlaca() throws Exception {
        OrdemServico os = TestDataFactory.criarOrdemServicoRecebida();
        when(buscarTodosPorPlacaUseCase.executar(anyString())).thenReturn(List.of(os));

        mockMvc.perform(get("/ordem-servicos/veiculo/placa/{placa}", "ABC1234"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve listar OS ativas paginadas com role MECANICO")
    void deveListarAtivasPaginadas_quandoMecanico() throws Exception {
        OrdemServico os = TestDataFactory.criarOrdemServicoEmExecucao();
        when(listarOrdensServicoAtivasUseCase.executar(any()))
                .thenReturn(new Pagina<>(List.of(os), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/ordem-servicos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(os.getId().toString()))
                .andExpect(jsonPath("$.content[0].situacao").value("EM_EXECUCAO"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve retornar pagina vazia")
    void deveRetornarPaginaVazia() throws Exception {
        when(listarOrdensServicoAtivasUseCase.executar(any()))
                .thenReturn(new Pagina<>(List.of(), 3, 20, 0, 0, false, true));

        mockMvc.perform(get("/ordem-servicos").param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve rejeitar parametros de paginacao invalidos")
    void deveRejeitarPaginacaoInvalida() throws Exception {
        mockMvc.perform(get("/ordem-servicos").param("page", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/ordem-servicos").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve negar listagem para usuario que nao e MECANICO")
    void deveNegarListagem_quandoNaoMecanico() throws Exception {
        mockMvc.perform(get("/ordem-servicos"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve cancelar OS")
    void deveCancelar() throws Exception {
        OrdemServico os = TestDataFactory.criarOrdemServicoInativa();
        when(cancelarUseCase.executar(any())).thenReturn(os);

        mockMvc.perform(patch("/ordem-servicos/{id}/cancelar", os.getId()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve devolver o histórico da OS em ordem cronológica")
    void deveDevolverHistorico() throws Exception {
        UUID osId = UUID.randomUUID();
        when(buscarHistoricoUseCase.executar(osId)).thenReturn(List.of(
                new EntradaHistorico(osId, Instant.parse("2026-11-17T10:00:00Z"), UUID.randomUUID(),
                        null, "RECEBIDA", "OrdemServicoAberta"),
                new EntradaHistorico(osId, Instant.parse("2026-11-17T10:01:00Z"), UUID.randomUUID(),
                        "RECEBIDA", "EM_DIAGNOSTICO", "DiagnosticoAberto")));

        mockMvc.perform(get("/ordem-servicos/{id}/historico", osId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].situacaoNova").value("RECEBIDA"))
                .andExpect(jsonPath("$[1].situacaoAnterior").value("RECEBIDA"))
                .andExpect(jsonPath("$[1].evento").value("DiagnosticoAberto"));
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve devolver o estado da saga da OS")
    void deveDevolverSaga() throws Exception {
        UUID osId = UUID.randomUUID();
        SagaOrdemServico saga = SagaOrdemServico.iniciar(osId, Instant.parse("2026-11-17T10:00:00Z"));
        when(orquestradorSaga.buscarPorOsId(osId)).thenReturn(saga);

        mockMvc.perform(get("/ordem-servicos/{id}/saga", osId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passoAtual").value("ABERTURA"))
                .andExpect(jsonPath("$.estado").value("EM_ANDAMENTO"));
    }
}
