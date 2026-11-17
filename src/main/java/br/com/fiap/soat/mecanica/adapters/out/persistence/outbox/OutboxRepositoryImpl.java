package br.com.fiap.soat.mecanica.adapters.out.persistence.outbox;

import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.MensagemSaida;
import br.com.fiap.soat.mecanica.application.saga.OutboxRepository;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

    private static final int TAMANHO_MAXIMO_ERRO = 2000;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public UUID registrar(MensagemSaida mensagem) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO outbox (id, destino, tipo, saga_id, os_id, correlation_id, causation_id,
                                            payload, traceparent, criado_em)
                        VALUES (:id, :destino, :tipo, :sagaId, :osId, :correlationId, :causationId,
                                CAST(:payload AS jsonb), :traceparent, :criadoEm)
                        """)
                .param("id", id)
                .param("destino", mensagem.destino().name())
                .param("tipo", mensagem.tipo())
                .param("sagaId", mensagem.sagaId())
                .param("osId", mensagem.osId())
                .param("correlationId", mensagem.correlationId())
                .param("causationId", mensagem.causationId())
                .param("payload", serializar(mensagem.payload()))
                .param("traceparent", RastreamentoMensagens.traceparentAtual())
                .param("criadoEm", OffsetDateTime.now(clock))
                .update();
        return id;
    }

    @Override
    public List<MensagemOutbox> travarPendentes(int limite) {
        return jdbcClient.sql("""
                        SELECT id, destino, tipo, saga_id, os_id, correlation_id, causation_id,
                               payload::text AS payload, traceparent, criado_em
                        FROM outbox
                        WHERE publicado_em IS NULL
                        ORDER BY criado_em
                        LIMIT :limite
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limite", limite)
                .query((rs, linha) -> paraMensagem(rs))
                .list();
    }

    @Override
    public void marcarPublicada(UUID id) {
        jdbcClient.sql("UPDATE outbox SET publicado_em = :agora, ultimo_erro = NULL WHERE id = :id")
                .param("agora", OffsetDateTime.now(clock))
                .param("id", id)
                .update();
    }

    @Override
    public void registrarFalha(UUID id, String erro) {
        String resumo = erro == null ? null : erro.substring(0, Math.min(erro.length(), TAMANHO_MAXIMO_ERRO));
        jdbcClient.sql("UPDATE outbox SET tentativas = tentativas + 1, ultimo_erro = :erro WHERE id = :id")
                .param("erro", resumo)
                .param("id", id)
                .update();
    }

    private MensagemOutbox paraMensagem(ResultSet rs) throws SQLException {
        try {
            return new MensagemOutbox(
                    rs.getObject("id", UUID.class),
                    DestinoMensagem.valueOf(rs.getString("destino")),
                    rs.getString("tipo"),
                    rs.getObject("saga_id", UUID.class),
                    rs.getObject("os_id", UUID.class),
                    rs.getString("correlation_id"),
                    rs.getObject("causation_id", UUID.class),
                    objectMapper.readTree(rs.getString("payload")),
                    rs.getString("traceparent"),
                    rs.getObject("criado_em", OffsetDateTime.class).toInstant()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload inválido na outbox", e);
        }
    }

    private String serializar(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar payload da outbox", e);
        }
    }
}
