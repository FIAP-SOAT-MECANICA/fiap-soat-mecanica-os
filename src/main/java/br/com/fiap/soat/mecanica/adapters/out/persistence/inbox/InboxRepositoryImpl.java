package br.com.fiap.soat.mecanica.adapters.out.persistence.inbox;

import br.com.fiap.soat.mecanica.application.saga.InboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * INSERT ... ON CONFLICT em vez de SELECT + INSERT: dois consumidores com a mesma mensagem
 * não passam juntos; o segundo espera a trava da chave primária e recebe 0 linhas.
 * JdbcClient participa da mesma transação do JpaTransactionManager.
 */
@Repository
@RequiredArgsConstructor
public class InboxRepositoryImpl implements InboxRepository {

    private final JdbcClient jdbcClient;

    @Override
    public boolean registrarSeNova(UUID messageId, String tipo) {
        int inseridas = jdbcClient.sql("""
                        INSERT INTO mensagem_processada (message_id, tipo)
                        VALUES (:messageId, :tipo)
                        ON CONFLICT (message_id) DO NOTHING
                        """)
                .param("messageId", messageId)
                .param("tipo", tipo)
                .update();
        return inseridas == 1;
    }
}
