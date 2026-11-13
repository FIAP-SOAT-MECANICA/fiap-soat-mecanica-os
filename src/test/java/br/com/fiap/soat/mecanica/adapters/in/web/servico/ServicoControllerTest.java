package br.com.fiap.soat.mecanica.adapters.in.web.servico;

import br.com.fiap.soat.mecanica.adapters.in.web.security.CustomUserDetailsService;
import br.com.fiap.soat.mecanica.adapters.out.security.JwtService;
import br.com.fiap.soat.mecanica.application.servico.AlterarServicoUseCase;
import br.com.fiap.soat.mecanica.application.servico.AtivarServicoUseCase;
import br.com.fiap.soat.mecanica.application.servico.BuscarServicoPorIdUseCase;
import br.com.fiap.soat.mecanica.application.servico.CadastrarServicoUseCase;
import br.com.fiap.soat.mecanica.application.servico.InativarServicoUseCase;
import br.com.fiap.soat.mecanica.config.SecurityConfig;
import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.soat.mecanica.domain.servico.Servico;
import br.com.fiap.soat.mecanica.util.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ServicoController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(SecurityConfig.class)
class ServicoControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CadastrarServicoUseCase cadastrarUseCase;
    @MockitoBean
    private AlterarServicoUseCase alterarUseCase;
    @MockitoBean
    private AtivarServicoUseCase ativarUseCase;
    @MockitoBean
    private InativarServicoUseCase inativarUseCase;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private BuscarServicoPorIdUseCase buscarServicoPorIdUseCase;


    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve cadastrar serviço com role MECANICO")
    void deveCadastrar_quandoMecanico() throws Exception {
        Servico servico = TestDataFactory.criarServicoValido();
        when(cadastrarUseCase.executar(anyString(), anyString())).thenReturn(servico);

        String json = """
                {"nome":"Troca de Óleo","descricao":"Desc"}
                """;

        mockMvc.perform(post("/servicos")
                        .contentType(MediaType.APPLICATION_JSON)
                .content(json).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve alterar servico")
    void deveAlterar() throws Exception {
        Servico servico = TestDataFactory.criarServicoValido();
        when(alterarUseCase.executar(any(), anyString(), anyString())).thenReturn(servico);

        String json = """
                {"nome":"Troca de oleo","descricao":"Descricao alterada"}
                """;

        mockMvc.perform(put("/servicos/{id}", servico.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve ativar servico")
    void deveAtivar() throws Exception {
        Servico servico = TestDataFactory.criarServicoValido();
        when(ativarUseCase.executar(any())).thenReturn(servico);

        mockMvc.perform(patch("/servicos/{id}/ativar", servico.getId()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve inativar servico")
    void deveInativar() throws Exception {
        Servico servico = TestDataFactory.criarServicoValido();
        when(inativarUseCase.executar(any())).thenReturn(servico);

        mockMvc.perform(patch("/servicos/{id}/inativar", servico.getId()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve buscar serviço por Id")
    void  deveBuscar_quandoId() throws Exception {
        Servico servico = TestDataFactory.criarServicoValido();
        when(buscarServicoPorIdUseCase.executar(any())).thenReturn(servico);

        mockMvc.perform(get("/servicos/{id}", servico.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(servico.getId().toString()))
                .andExpect(jsonPath("$.nome").value("Troca de Óleo"))
                .andExpect(jsonPath("$.statusRecursoEnum").value("ATIVO"));
    }

    @Test
    @WithMockUser(roles = "MECANICO")
    @DisplayName("Deve retornar 404 quando serviço não existe")
    void deveRetornar404_quandoServicoNaoExiste() throws Exception {
        when(buscarServicoPorIdUseCase.executar(any())).
            thenThrow(new RecursoNaoEncontradoException("Serviço não encontrado"));

        mockMvc.perform(get("/servicos/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve negar busca de serviço quando role não é MECANICO")
    void deveNegar_quandoRoleNaoMecanico() throws Exception {
        mockMvc.perform(get("/servicos/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
