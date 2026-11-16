package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.application.saga.Comando;
import br.com.fiap.soat.mecanica.application.saga.DestinoComando;
import br.com.fiap.soat.mecanica.application.saga.PublicadorDeComandos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class SqsPublicadorDeComandos implements PublicadorDeComandos {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<DestinoComando, String> filas;

    public SqsPublicadorDeComandos(SqsClient sqsClient,
                                   ObjectMapper objectMapper,
                                   Clock clock,
                                   @Value("${mecanica.messaging.execucao-command-queue-url:}") String filaExecucao) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.filas = Map.of(DestinoComando.EXECUCAO, filaExecucao);
    }

    @Override
    public UUID publicar(Comando comando) {
        MessageEnvelope envelope = new MessageEnvelope(
                UUID.randomUUID(),
                comando.tipo(),
                MessageEnvelope.VERSAO_SUPORTADA,
                comando.sagaId(),
                comando.osId(),
                comando.correlationId(),
                comando.causationId(),
                Instant.now(clock),
                objectMapper.valueToTree(comando.payload())
        );
        envelope.validar();

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(filaDe(comando.destino()))
                .messageBody(serializar(envelope))
                .build()
         );

        return envelope.messageId();
    }

    private String filaDe(DestinoComando destino) {
        String url = filas.get(destino);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("URL da fila não configurada para o destino " + destino);
        }
        return url;
    }

    private String serializar(MessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o envelope " + envelope.messageId(), e);
        }
    }
}
