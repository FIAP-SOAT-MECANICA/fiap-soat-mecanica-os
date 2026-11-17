package br.com.fiap.soat.mecanica.adapters.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Api-Key";

    // Cada serviço consumidor: nome (vira o principal), chave e escopos concedidos.
    private record ServicoCliente(String nome, String chave, List<String> escopos) {}

    private final List<ServicoCliente> servicos;

    public ApiKeyAuthenticationFilter(
            @Value("${seguranca.api-key.auth-lambda:}") String chaveAuthLambda,
            @Value("${seguranca.api-key.execucao:}") String chaveExecucao
    ) {
        this.servicos = List.of(
                new ServicoCliente("auth-lambda", chaveAuthLambda, List.of("SCOPE_clientes:situacao:ler")),
                new ServicoCliente("execucao", chaveExecucao, List.of("SCOPE_servicos:ler"))
        );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String chaveRecebida = request.getHeader(HEADER);

        // Sem cabeçalho, ou alguém já autenticado (ex.: JWT): não interfere.
        if (chaveRecebida == null
                || chaveRecebida.isBlank()
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        for (ServicoCliente servico : servicos) {
            if (chaveConfere(servico.chave(), chaveRecebida)) {
                var autenticacao = new UsernamePasswordAuthenticationToken(
                        servico.nome(),
                        null,
                        servico.escopos().stream().map(SimpleGrantedAuthority::new).toList()
                );
                SecurityContextHolder.getContext().setAuthentication(autenticacao);
                break;
            }
        }

        // Chave errada: segue sem autenticar. A regra da rota responde 401/403 adiante.
        filterChain.doFilter(request, response);
    }

    private boolean chaveConfere(String chaveConfigurada, String chaveRecebida) {
        // Fail-closed: chave não configurada nunca autentica ninguém.
        if (chaveConfigurada == null || chaveConfigurada.isBlank()) {
            return false;
        }

        // Comparação em tempo constante, contra timing attack.
        return MessageDigest.isEqual(
                chaveConfigurada.getBytes(StandardCharsets.UTF_8),
                chaveRecebida.getBytes(StandardCharsets.UTF_8)
        );
    }
}
