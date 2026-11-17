package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.HistoricoOrdemServicoRepository;
import br.com.fiap.soat.mecanica.application.saga.OutboxRepository;
import br.com.fiap.soat.mecanica.application.saga.PublicadorDeComandos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Liga o job da outbox. Desligado por padrão (testes, Kind sem AWS); no EKS o cd.yml liga com
 * MESSAGING_OUTBOX_ENABLED=true. Desligado, as mensagens só se acumulam na tabela: nada se perde.
 */
@Configuration
@ConditionalOnProperty(name = "mecanica.messaging.outbox.habilitado", havingValue = "true")
public class PublicadorDaOutboxConfig {

    private final PublicadorDaOutbox publicador;

    public PublicadorDaOutboxConfig(OutboxRepository outboxRepository,
                                    PublicadorDeComandos publicadorDeComandos,
                                    HistoricoOrdemServicoRepository historicoRepository,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${mecanica.messaging.outbox.tamanho-lote:20}") int tamanhoLote) {
        this.publicador = new PublicadorDaOutbox(outboxRepository, publicadorDeComandos, historicoRepository,
                new TransactionTemplate(transactionManager), tamanhoLote);
    }

    @Bean
    public PublicadorDaOutbox publicadorDaOutbox() {
        return publicador;
    }

    @Scheduled(fixedDelayString = "${mecanica.messaging.outbox.intervalo-ms:500}")
    public void publicarPendentes() {
        publicador.publicarLote();
    }
}
