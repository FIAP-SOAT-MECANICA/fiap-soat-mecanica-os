package br.com.fiap.soat.mecanica.adapters.in.web.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticationFilterTest {

    private static final String HEADER = "X-Api-Key";

    private final ApiKeyAuthenticationFilter filtro =
            new ApiKeyAuthenticationFilter("chave-lambda", "chave-execucao");

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Deve autenticar o Execução com escopo servicos:ler quando a chave confere")
    void deveAutenticarExecucao_quandoChaveValida() throws Exception {
        MockFilterChain chain = executar(filtro, "chave-execucao");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getName()).isEqualTo("execucao");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_servicos:ler");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Deve autenticar a Lambda com escopo clientes:situacao:ler quando a chave confere")
    void deveAutenticarLambda_quandoChaveValida() throws Exception {
        MockFilterChain chain = executar(filtro, "chave-lambda");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getName()).isEqualTo("auth-lambda");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_clientes:situacao:ler");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Não deve autenticar quando a chave está errada, mas a cadeia continua")
    void naoDeveAutenticar_quandoChaveErrada() throws Exception {
        MockFilterChain chain = executar(filtro, "chave-errada");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Não deve autenticar quando o cabeçalho não é enviado")
    void naoDeveAutenticar_quandoSemCabecalho() throws Exception {
        MockFilterChain chain = executar(filtro, null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Fail-closed: chave não configurada nunca autentica, nem com cabeçalho vazio")
    void naoDeveAutenticar_quandoChaveNaoConfigurada() throws Exception {
        ApiKeyAuthenticationFilter filtroSemChave = new ApiKeyAuthenticationFilter("", "");

        executar(filtroSemChave, "");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        executar(filtroSemChave, " ");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Não deve sobrescrever uma autenticação já existente (ex.: JWT)")
    void naoDeveSobrescrever_quandoJaAutenticado() throws Exception {
        var usuario = new UsernamePasswordAuthenticationToken(
                "mecanico@oficina.com", null, List.of(new SimpleGrantedAuthority("ROLE_MECANICO")));
        SecurityContextHolder.getContext().setAuthentication(usuario);

        executar(filtro, "chave-execucao");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(usuario);
    }

    private MockFilterChain executar(ApiKeyAuthenticationFilter alvo, String chave) throws Exception {
        var request = new MockHttpServletRequest();
        if (chave != null) {
            request.addHeader(HEADER, chave);
        }
        var chain = new MockFilterChain();
        alvo.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
