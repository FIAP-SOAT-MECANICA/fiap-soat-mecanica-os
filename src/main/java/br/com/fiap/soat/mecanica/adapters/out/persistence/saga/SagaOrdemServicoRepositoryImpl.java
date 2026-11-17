package br.com.fiap.soat.mecanica.adapters.out.persistence.saga;

import br.com.fiap.soat.mecanica.domain.saga.EstadoSagaEnum;
import br.com.fiap.soat.mecanica.domain.saga.ItemDiagnostico;
import br.com.fiap.soat.mecanica.domain.saga.PassoSagaEnum;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServico;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServicoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC em vez de JPA: as listas vão em colunas JSONB legíveis no psql (para o vídeo) e o
 * SELECT ... FOR UPDATE fica explícito. Participa da mesma transação dos repositórios JPA.
 */
@Repository
@RequiredArgsConstructor
public class SagaOrdemServicoRepositoryImpl implements SagaOrdemServicoRepository {

    private static final String COLUNAS = """
            saga_id, os_id, passo_atual, estado, passos_concluidos::text AS passos_concluidos,
            passos_compensados::text AS passos_compensados, itens_diagnostico::text AS itens_diagnostico,
            orcamento_id, ciclo_reserva_id, motivo, criado_em, atualizado_em
            """;

    private static final TypeReference<List<PassoSagaEnum>> LISTA_PASSOS = new TypeReference<>() { };
    private static final TypeReference<List<ItemDiagnostico>> LISTA_ITENS = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    @Override
    public void salvar(SagaOrdemServico saga) {
        jdbcClient.sql("""
                        INSERT INTO saga_ordem_servico (saga_id, os_id, passo_atual, estado, passos_concluidos,
                                                        passos_compensados, itens_diagnostico, orcamento_id,
                                                        ciclo_reserva_id, motivo, criado_em, atualizado_em)
                        VALUES (:sagaId, :osId, :passoAtual, :estado, CAST(:concluidos AS jsonb),
                                CAST(:compensados AS jsonb), CAST(:itens AS jsonb), :orcamentoId,
                                :cicloReservaId, :motivo, :criadoEm, :atualizadoEm)
                        ON CONFLICT (saga_id) DO UPDATE SET
                            passo_atual        = EXCLUDED.passo_atual,
                            estado             = EXCLUDED.estado,
                            passos_concluidos  = EXCLUDED.passos_concluidos,
                            passos_compensados = EXCLUDED.passos_compensados,
                            itens_diagnostico  = EXCLUDED.itens_diagnostico,
                            orcamento_id       = EXCLUDED.orcamento_id,
                            ciclo_reserva_id   = EXCLUDED.ciclo_reserva_id,
                            motivo             = EXCLUDED.motivo,
                            atualizado_em      = EXCLUDED.atualizado_em
                        """)
                .param("sagaId", saga.getSagaId())
                .param("osId", saga.getOsId())
                .param("passoAtual", saga.getPassoAtual().name())
                .param("estado", saga.getEstado().name())
                .param("concluidos", json(saga.getPassosConcluidos()))
                .param("compensados", json(saga.getPassosCompensados()))
                .param("itens", json(saga.getItensDiagnostico()))
                .param("orcamentoId", saga.getOrcamentoId())
                .param("cicloReservaId", saga.getCicloReservaId())
                .param("motivo", saga.getMotivo())
                .param("criadoEm", saga.getCriadoEm().atOffset(ZoneOffset.UTC))
                .param("atualizadoEm", saga.getAtualizadoEm().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<SagaOrdemServico> buscarPorOsId(UUID osId) {
        return jdbcClient.sql("SELECT " + COLUNAS + " FROM saga_ordem_servico WHERE os_id = :osId")
                .param("osId", osId)
                .query((rs, linha) -> paraSaga(rs))
                .optional();
    }

    @Override
    public Optional<SagaOrdemServico> buscarPorOsIdParaAtualizar(UUID osId) {
        return jdbcClient.sql("SELECT " + COLUNAS + " FROM saga_ordem_servico WHERE os_id = :osId FOR UPDATE")
                .param("osId", osId)
                .query((rs, linha) -> paraSaga(rs))
                .optional();
    }

    private SagaOrdemServico paraSaga(ResultSet rs) throws SQLException {
        return SagaOrdemServico.reconstruir(
                rs.getObject("saga_id", UUID.class),
                rs.getObject("os_id", UUID.class),
                PassoSagaEnum.valueOf(rs.getString("passo_atual")),
                EstadoSagaEnum.valueOf(rs.getString("estado")),
                ler(rs.getString("passos_concluidos"), LISTA_PASSOS),
                ler(rs.getString("passos_compensados"), LISTA_PASSOS),
                ler(rs.getString("itens_diagnostico"), LISTA_ITENS),
                rs.getObject("orcamento_id", UUID.class),
                rs.getObject("ciclo_reserva_id", UUID.class),
                rs.getString("motivo"),
                rs.getObject("criado_em", OffsetDateTime.class).toInstant(),
                rs.getObject("atualizado_em", OffsetDateTime.class).toInstant()
        );
    }

    private <T> List<T> ler(String json, TypeReference<List<T>> tipo) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON inválido na saga", e);
        }
    }

    private String json(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a saga", e);
        }
    }
}
