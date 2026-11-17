package br.com.fiap.soat.mecanica.application.saga;

/**
 * Evento de um passo que a saga ainda não alcançou (SNS + SQS padrão não garantem ordem).
 * Lançar faz a mensagem voltar para a fila e ser tentada de novo depois do visibility timeout;
 * se nunca encaixar, vai para a DLQ após 5 tentativas.
 */
public class EventoForaDeOrdemException extends RuntimeException {
    public EventoForaDeOrdemException(String message) {
        super(message);
    }
}
