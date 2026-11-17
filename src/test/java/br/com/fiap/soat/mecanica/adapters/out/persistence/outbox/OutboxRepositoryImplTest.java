package br.com.fiap.soat.mecanica.adapters.out.persistence.outbox;

import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.MensagemSaida;
import br.com.fiap.soat.mecanica.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({OutboxRepositoryImpl.class, OutboxRepositoryImplTest.Config.class})
class OutboxRepositoryImplTest extends PostgresIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired private OutboxRepositoryImpl repository;
    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void limpar() {
        jdbcClient.sql("DELETE FROM outbox").update();
    }

    @Test
    @DisplayName("Deve gravar a mensagem pendente com payload, ids e traceparent do contexto atual")
    void deveRegistrarPendente() {
        UUID sagaId = UUID.randomUUID();
        UUID osId = UUID.randomUUID();
        UUID causa = UUID.randomUUID();

        UUID id;
        SpanContext contexto = SpanContext.create(TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Span.wrap(contexto).makeCurrent()) {
            id = repository.registrar(new MensagemSaida(DestinoMensagem.EXECUCAO, "AbrirDiagnostico", sagaId, osId,
                    "corr-1", causa, Map.of("osId", osId, "placa", "ABC1D23")));
        }

        List<MensagemOutbox> pendentes = repository.travarPendentes(10);
        assertThat(pendentes).hasSize(1);
        MensagemOutbox mensagem = pendentes.get(0);
        assertThat(mensagem.id()).isEqualTo(id);
        assertThat(mensagem.destino()).isEqualTo(DestinoMensagem.EXECUCAO);
        assertThat(mensagem.tipo()).isEqualTo("AbrirDiagnostico");
        assertThat(mensagem.sagaId()).isEqualTo(sagaId);
        assertThat(mensagem.osId()).isEqualTo(osId);
        assertThat(mensagem.correlationId()).isEqualTo("corr-1");
        assertThat(mensagem.causationId()).isEqualTo(causa);
        assertThat(mensagem.payload().get("placa").asText()).isEqualTo("ABC1D23");
        assertThat(mensagem.traceparent()).isEqualTo("00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        assertThat(mensagem.criadoEm()).isEqualTo(Instant.parse("2026-11-17T12:00:00Z"));
    }

    @Test
    @DisplayName("Publicada sai das pendentes; falha mantém pendente e conta a tentativa")
    void deveMarcarPublicadaERegistrarFalha() {
        UUID publicada = registrar("IniciarExecucao");
        UUID falhou = registrar("ReservarPecas");

        repository.marcarPublicada(publicada);
        repository.registrarFalha(falhou, "SqsException: fila não existe");

        assertThat(repository.travarPendentes(10)).extracting(MensagemOutbox::id).containsExactly(falhou);
        Map<String, Object> linha = jdbcClient.sql("SELECT tentativas, ultimo_erro FROM outbox WHERE id = :id")
                .param("id", falhou).query().singleRow();
        assertThat(linha.get("tentativas")).isEqualTo(1);
        assertThat(linha.get("ultimo_erro")).isEqualTo("SqsException: fila não existe");
    }

    @Test
    @DisplayName("Sem rastro ativo, o traceparent fica nulo")
    void deveGravarSemTraceparent() {
        registrar("LiberarReservaPecas");

        assertThat(repository.travarPendentes(10).get(0).traceparent()).isNull();
    }

    private UUID registrar(String tipo) {
        UUID osId = UUID.randomUUID();
        return repository.registrar(new MensagemSaida(DestinoMensagem.EXECUCAO, tipo, UUID.randomUUID(), osId,
                null, null, Map.of("osId", osId)));
    }
}
