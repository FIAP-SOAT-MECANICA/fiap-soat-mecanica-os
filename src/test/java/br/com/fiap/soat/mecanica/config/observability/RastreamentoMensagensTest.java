package br.com.fiap.soat.mecanica.config.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RastreamentoMensagensTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Test
    @DisplayName("Sem rastro ativo não há traceparent")
    void semRastro() {
        assertThat(RastreamentoMensagens.traceparentAtual()).isNull();
    }

    @Test
    @DisplayName("Captura o traceparent W3C do span atual")
    void deveCapturar() {
        SpanContext contexto = SpanContext.create(TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Span.wrap(contexto).makeCurrent()) {
            assertThat(RastreamentoMensagens.traceparentAtual()).isEqualTo(TRACEPARENT);
        }
    }

    @Test
    @DisplayName("Ida e volta: o que roda dentro do span continua no mesmo trace do produtor")
    void deveContinuarOTrace() {
        String traceIdDentro = RastreamentoMensagens.executarComo("consumir X", SpanKind.CONSUMER, TRACEPARENT,
                () -> Span.current().getSpanContext().getTraceId());

        assertThat(traceIdDentro).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("Sem traceparent, executa normalmente; exceção é propagada")
    void semTraceparentEExcecao() {
        assertThat(RastreamentoMensagens.executarComo("x", SpanKind.INTERNAL, null, () -> 42)).isEqualTo(42);
        assertThatThrownBy(() -> RastreamentoMensagens.executarComo("x", SpanKind.INTERNAL, "", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }
}
