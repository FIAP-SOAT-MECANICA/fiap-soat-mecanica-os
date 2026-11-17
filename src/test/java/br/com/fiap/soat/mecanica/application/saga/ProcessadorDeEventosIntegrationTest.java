package br.com.fiap.soat.mecanica.application.saga;

import br.com.fiap.soat.mecanica.adapters.out.persistence.inbox.InboxRepositoryImpl;
import br.com.fiap.soat.mecanica.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Critério de pronto da 5.4: o mesmo messageId processado duas vezes produz um efeito só.
 * NOT_SUPPORTED: sem a transação de teste em volta, cada processar() confirma ou desfaz de verdade.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({InboxRepositoryImpl.class, ProcessadorDeEventos.class,
        ProcessadorDeEventosIntegrationTest.TratadorQueConta.class,
        ProcessadorDeEventosIntegrationTest.TratadorQueFalhaUmaVez.class})
class ProcessadorDeEventosIntegrationTest extends PostgresIntegrationTest {

    @Autowired private ProcessadorDeEventos processador;
    @Autowired private TratadorQueConta tratadorQueConta;
    @Autowired private TratadorQueFalhaUmaVez tratadorQueFalha;
    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void limpar() {
        jdbcClient.sql("DELETE FROM mensagem_processada").update();
        tratadorQueConta.chamadas.set(0);
        tratadorQueFalha.tentativas.set(0);
    }

    @Test
    @DisplayName("Mesmo messageId processado duas vezes produz um efeito só")
    void deveIgnorarDuplicado() {
        EventoRecebido evento = evento("PecasReservadas");

        ResultadoProcessamento primeira = processador.processar(evento);
        ResultadoProcessamento segunda = processador.processar(evento);

        assertThat(primeira).isEqualTo(ResultadoProcessamento.PROCESSADO);
        assertThat(segunda).isEqualTo(ResultadoProcessamento.DUPLICADO);
        assertThat(tratadorQueConta.chamadas).hasValue(1);
        assertThat(linhasNaInbox(evento.messageId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Falha no tratador desfaz o registro na inbox e permite reprocessar")
    void deveDesfazerInboxQuandoTratadorFalha() {
        EventoRecebido evento = evento("ExecucaoFinalizada");

        assertThatThrownBy(() -> processador.processar(evento)).isInstanceOf(IllegalStateException.class);
        assertThat(linhasNaInbox(evento.messageId())).isZero();

        assertThat(processador.processar(evento)).isEqualTo(ResultadoProcessamento.PROCESSADO);
        assertThat(tratadorQueFalha.tentativas).hasValue(2);
        assertThat(linhasNaInbox(evento.messageId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Evento sem tratador não é registrado na inbox")
    void naoDeveRegistrarSemTratador() {
        EventoRecebido evento = evento("EventoQueNinguemTrata");

        assertThat(processador.processar(evento)).isEqualTo(ResultadoProcessamento.SEM_TRATADOR);
        assertThat(linhasNaInbox(evento.messageId())).isZero();
    }

    @Test
    @DisplayName("Dois tratadores para o mesmo tipo é erro de configuração")
    void deveRecusarTratadorDuplicado() {
        InboxRepository inbox = (id, tipo) -> true;
        List<TratadorDeEventos> tratadores = List.of(new TratadorQueConta(), new TratadorQueConta());

        assertThatThrownBy(() -> new ProcessadorDeEventos(inbox, tratadores))
                .isInstanceOf(IllegalStateException.class);
    }

    private int linhasNaInbox(UUID messageId) {
        return jdbcClient.sql("SELECT count(*) FROM mensagem_processada WHERE message_id = :id")
                .param("id", messageId).query(Integer.class).single();
    }

    private static EventoRecebido evento(String tipo) {
        UUID osId = UUID.randomUUID();
        return new EventoRecebido(UUID.randomUUID(), tipo, UUID.randomUUID(), osId, "req-1", null,
                JsonNodeFactory.instance.objectNode().put("osId", osId.toString()));
    }

    static class TratadorQueConta implements TratadorDeEventos {
        final AtomicInteger chamadas = new AtomicInteger();

        @Override
        public Set<String> tipos() {
            return Set.of("PecasReservadas");
        }

        @Override
        public void tratar(EventoRecebido evento) {
            chamadas.incrementAndGet();
        }
    }

    static class TratadorQueFalhaUmaVez implements TratadorDeEventos {
        final AtomicInteger tentativas = new AtomicInteger();

        @Override
        public Set<String> tipos() {
            return Set.of("ExecucaoFinalizada");
        }

        @Override
        public void tratar(EventoRecebido evento) {
            if (tentativas.incrementAndGet() == 1) {
                throw new IllegalStateException("falha simulada");
            }
        }
    }
}
