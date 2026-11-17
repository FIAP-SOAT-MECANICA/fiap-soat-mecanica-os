package br.com.fiap.soat.mecanica.config.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Propagação do contexto de rastreamento (W3C traceparent) pelas mensagens (tarefa 7.1).
 *
 * O agente do OpenTelemetry propaga sozinho em HTTP, mas a outbox quebra a cadeia: quem publica
 * é um job agendado, sem o contexto da requisição que gerou a mensagem. Por isso o traceparent é
 * gravado na linha da outbox, vai como atributo da mensagem SQS e é reativado no consumidor.
 *
 * Usa o propagador W3C direto (mesmo formato do "tracecontext" configurado no Instrumentation do
 * k8s) e o tracer global, que é o do agente em produção e um no-op nos testes. O no-op preserva o
 * contexto do pai, então a propagação pode ser testada sem o agente.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RastreamentoMensagens {

    public static final String ATRIBUTO = "traceparent";
    private static final String INSTRUMENTACAO = "fiap-soat-mecanica-os";

    private static final TextMapGetter<Map<String, String>> LEITOR = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    /** traceparent do contexto atual, ou null se não há rastro ativo. */
    public static String traceparentAtual() {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        return carrier.get(ATRIBUTO);
    }

    /**
     * Executa a ação dentro de um span filho do traceparent recebido. Sem traceparent,
     * o span é filho do contexto atual.
     */
    public static <T> T executarComo(String nomeSpan, SpanKind tipo, String traceparent, Supplier<T> acao) {
        Context pai = traceparent == null || traceparent.isBlank()
                ? Context.current()
                : W3CTraceContextPropagator.getInstance().extract(Context.root(), Map.of(ATRIBUTO, traceparent), LEITOR);

        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTACAO)
                .spanBuilder(nomeSpan)
                .setParent(pai)
                .setSpanKind(tipo)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return acao.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
