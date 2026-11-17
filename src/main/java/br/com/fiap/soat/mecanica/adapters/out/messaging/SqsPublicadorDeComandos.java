package br.com.fiap.soat.mecanica.adapters.out.messaging;

import br.com.fiap.soat.mecanica.application.saga.DestinoMensagem;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.PublicadorDeComandos;
import br.com.fiap.soat.mecanica.config.observability.RastreamentoMensagens;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.EnumMap;
import java.util.Map;

@Component
public class SqsPublicadorDeComandos implements PublicadorDeComandos {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final Map<DestinoMensagem, String> filas = new EnumMap<>(DestinoMensagem.class);

    public SqsPublicadorDeComandos(SqsClient sqsClient,
                                   ObjectMapper objectMapper,
                                   @Value("${mecanica.messaging.execucao-command-queue-url:}") String filaExecucao,
                                   @Value("${mecanica.messaging.billing-command-queue-url:}") String filaBilling) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.filas.put(DestinoMensagem.EXECUCAO, filaExecucao);
        this.filas.put(DestinoMensagem.BILLING, filaBilling);
    }

    @Override
    public void publicar(MensagemOutbox mensagem) {
        MessageEnvelope envelope = new MessageEnvelope(
                mensagem.id(),
                mensagem.tipo(),
                MessageEnvelope.VERSAO_SUPORTADA,
                mensagem.sagaId(),
                mensagem.osId(),
                mensagem.correlationId(),
                mensagem.causationId(),
                mensagem.criadoEm(),
                mensagem.payload()
        );
        envelope.validar();

        SendMessageRequest.Builder requisicao = SendMessageRequest.builder()
                .queueUrl(filaDe(mensagem.destino()))
                .messageBody(serializar(envelope));

        if (mensagem.traceparent() != null) {
            requisicao.messageAttributes(Map.of(RastreamentoMensagens.ATRIBUTO, MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(mensagem.traceparent())
                    .build()));
        }

        sqsClient.sendMessage(requisicao.build());
    }

    private String filaDe(DestinoMensagem destino) {
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
