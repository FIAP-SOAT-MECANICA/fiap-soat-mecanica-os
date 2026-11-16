package br.com.fiap.soat.mecanica.adapters.in.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.MessageEnvelope;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.TratadorDeEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "mecanica.messaging.consumidor.habilitado", havingValue = "true")
public class SqsConsumidorDeRespostas {

    private static final int MAXIMO_POR_LEITURA = 10;
    private static final int ESPERA_LONGA_SEGUNDOS = 20;

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String filaUrl;
    private final Map<String, TratadorDeEventos> tratadorDeEventosMap;

    public SqsConsumidorDeRespostas(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            @Value("${mecanica.messaging.response-queue-url}") String filaUrl,
            List<TratadorDeEventos> tratadores
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.filaUrl = filaUrl;
        this.tratadorDeEventosMap = tratadores.stream().collect(Collectors.toMap(TratadorDeEventos::tipo, Function.identity()));
    }

    @Scheduled(fixedDelay = 1000)
    public void consumir() {
        List<Message> mensagens = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(filaUrl)
                        .maxNumberOfMessages(MAXIMO_POR_LEITURA)
                        .waitTimeSeconds(ESPERA_LONGA_SEGUNDOS)
                .build()).messages();

        mensagens.forEach(this::processar);
    }

    private void processar(Message mensagem) {
        try {
            MessageEnvelope envelope = objectMapper.readValue(mensagem.body(), MessageEnvelope.class);
            envelope.validar();

            TratadorDeEventos tratadorDeEventos = tratadorDeEventosMap.get(envelope.type());
            if (tratadorDeEventos == null) {
                log.warn("Evento sem tratador; mensagem mantida na fila. type={} messageId={}",
                        envelope.type(), envelope.messageId());
                return;
            }

            tratadorDeEventos.tratar(new EventoRecebido(
                    envelope.messageId(),
                    envelope.type(),
                    envelope.sagaId(),
                    envelope.osId(),
                    envelope.causationId(),
                    envelope.payload()));

            sqsClient.deleteMessage(r -> r.queueUrl(filaUrl).receiptHandle(mensagem.receiptHandle()));
        } catch (Exception e) {
            log.error("Falha ao processar mensagem; ela volta para a fila. sqsMessageId={}",
                    mensagem.messageId(), e);
        }
    }
}
