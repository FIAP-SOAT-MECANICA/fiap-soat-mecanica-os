package br.com.fiap.soat.mecanica.adapters.in.web.cliente;

import br.com.fiap.soat.mecanica.adapters.in.web.security.CustomUserDetailsService;
import br.com.fiap.soat.mecanica.adapters.out.security.JwtService;
import br.com.fiap.soat.mecanica.application.cliente.usecase.*;
import br.com.fiap.soat.mecanica.config.SecurityConfig;
import br.com.fiap.soat.mecanica.domain.cliente.Cliente;
import br.com.fiap.soat.mecanica.domain.exception.RecursoNaoEncontradoException;
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

@WebMvcTest(value = ClienteController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(SecurityConfig.class)
class ClienteControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CadastrarClienteUseCase cadastrarUseCase;
    @MockitoBean
    private AlterarClienteUseCase alterarUseCase;
    @MockitoBean
    private BuscarClientePorCpfUseCase buscarCpfUseCase;
    @MockitoBean
    private BuscarClientePorCnpjUseCase buscarCnpjUseCase;
    @MockitoBean
    private BuscarClientePorUsuarioIdUseCase buscarUsuarioIdUseCase;
    @MockitoBean
    private BuscarClienteAutenticadoUseCase buscarClienteAutenticadoUseCase;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve cadastrar cliente com role ATENDENTE")
    void deveCadastrar_quandoAtendente() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(cadastrarUseCase.executar(anyString(), anyString(), any(), anyString(), any())).thenReturn(cliente);

        String json = """
                {"nome":"Teste","cpf":"52998224725","email":"c@c.com"}
                """;

        mockMvc.perform(post("/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve buscar cliente por CPF")
    void deveBuscarPorCpf() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(buscarCpfUseCase.executar(anyString())).thenReturn(cliente);

        mockMvc.perform(get("/clientes/por-cpf/{cpf}", "52998224725"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_clientes:situacao:ler")
    @DisplayName("Deve devolver só id e status do cliente para quem tem o escopo de situação")
    void deveBuscarSituacaoPorCpf_quandoEscopoSituacao() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(buscarCpfUseCase.executar(anyString())).thenReturn(cliente);

        mockMvc.perform(get("/clientes/por-cpf/{cpf}/situacao", "52998224725"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cliente.getId().toString()))
                .andExpect(jsonPath("$.status").value(cliente.getStatus().name()))
                .andExpect(jsonPath("$.nome").doesNotExist())
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.telefone").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_clientes:situacao:ler")
    @DisplayName("Deve retornar 404 na situação quando o CPF não existe")
    void deveRetornar404NaSituacao_quandoCpfNaoExiste() throws Exception {
        when(buscarCpfUseCase.executar(anyString()))
                .thenThrow(new RecursoNaoEncontradoException("Cliente não encontrado"));

        mockMvc.perform(get("/clientes/por-cpf/{cpf}/situacao", "52998224725"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_servicos:ler")
    @DisplayName("Deve negar a situação do cliente para o escopo do Execução")
    void deveNegarSituacao_quandoEscopoDeOutroServico() throws Exception {
        mockMvc.perform(get("/clientes/por-cpf/{cpf}/situacao", "52998224725"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve negar a situação do cliente para usuário interno sem o escopo")
    void deveNegarSituacao_quandoAtendente() throws Exception {
        mockMvc.perform(get("/clientes/por-cpf/{cpf}/situacao", "52998224725"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve alterar cliente")
    void deveAlterar() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(alterarUseCase.executar(any(), anyString(), anyString())).thenReturn(cliente);

        String json = """
                {"nome":"Cliente Alterado","telefone":"11999887766"}
                """;

        mockMvc.perform(patch("/clientes/{id}", cliente.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve buscar cliente por CNPJ")
    void deveBuscarPorCnpj() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCnpj();
        when(buscarCnpjUseCase.executar(anyString())).thenReturn(cliente);

        mockMvc.perform(get("/clientes/por-cnpj/{cnpj}", "11222333000181"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve buscar clientes por usuario ID")
    void deveBuscarTodosPorUsuarioId() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(buscarUsuarioIdUseCase.executar(any())).thenReturn(List.of(cliente));

        mockMvc.perform(get("/clientes/por-usuario/{usuarioId}", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("Deve buscar dados do cliente autenticado com role CLIENTE")
    void deveBuscarAutenticado_quandoRoleCliente() throws Exception {
        Cliente cliente = TestDataFactory.criarClienteComCpf();
        when(buscarClienteAutenticadoUseCase.executar()).thenReturn(cliente);

        mockMvc.perform(get("/clientes/me"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ATENDENTE")
    @DisplayName("Deve negar acesso a /clientes/me quando role não é CLIENTE")
    void deveNegarAutenticado_quandoRoleDiferente() throws Exception {
        mockMvc.perform(get("/clientes/me"))
                .andExpect(status().isForbidden());
    }
}
