package br.com.fiap.soat.mecanica.adapters.in.messaging;

import br.com.fiap.soat.mecanica.adapters.out.messaging.MessageEnvelope;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.ProcessadorDeEventos;
import br.com.fiap.soat.mecanica.application.saga.ResultadoProcessamento;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.SpanKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "mecanica.messaging.consumidor.habilitado", havingValue = "true")
public class SqsConsumidorDeRespostas {

    private static final int MAXIMO_POR_LEITURA = 10;
    private static final int ESPERA_LONGA_SEGUNDOS = 20;

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String filaUrl;
    private final ProcessadorDeEventos processador;

    public SqsConsumidorDeRespostas(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            @Value("${mecanica.messaging.response-queue-url}") String filaUrl,
            ProcessadorDeEventos processador
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.filaUrl = filaUrl;
        this.processador = processador;
    }

    @Scheduled(fixedDelay = 1000)
    public void consumir() {
        List<Message> mensagens = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(filaUrl)
                        .maxNumberOfMessages(MAXIMO_POR_LEITURA)
                        .waitTimeSeconds(ESPERA_LONGA_SEGUNDOS)
                        .messageAttributeNames(RastreamentoMensagens.ATRIBUTO)
                .build()).messages();

        mensagens.forEach(this::processar);
    }

    private void processar(Message mensagem) {
        try {
            MessageEnvelope envelope = objectMapper.readValue(mensagem.body(), MessageEnvelope.class);
            envelope.validarEntrada();

            // Reativa o rastro de quem publicou: o processamento vira filho do span do produtor.
            ResultadoProcessamento resultado = RastreamentoMensagens.executarComo(
                    "consumir " + envelope.type(), SpanKind.CONSUMER, traceparent(mensagem),
                    () -> processador.processar(new EventoRecebido(
                            envelope.messageId(),
                            envelope.type(),
                            envelope.sagaId(),
                            envelope.osId(),
                            envelope.correlationId(),
                            envelope.causationId(),
                            envelope.payload())));

            if (resultado == ResultadoProcessamento.SEM_TRATADOR) {
                log.warn("Evento sem tratador; mensagem mantida na fila. type={} messageId={}",
                        envelope.type(), envelope.messageId());
                return;
            }

            // Só chega aqui depois do commit: PROCESSADO ou DUPLICADO podem sair da fila.
            sqsClient.deleteMessage(r -> r.queueUrl(filaUrl).receiptHandle(mensagem.receiptHandle()));
        } catch (Exception e) {
            log.error("Falha ao processar mensagem; ela volta para a fila. sqsMessageId={}",
                    mensagem.messageId(), e);
        }
    }

    private static String traceparent(Message mensagem) {
        MessageAttributeValue atributo = mensagem.messageAttributes().get(RastreamentoMensagens.ATRIBUTO);
        return atributo == null ? null : atributo.stringValue();
    }
}
