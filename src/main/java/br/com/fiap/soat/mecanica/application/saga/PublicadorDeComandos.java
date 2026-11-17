package br.com.fiap.soat.mecanica.application.saga;

public interface PublicadorDeComandos {

    /**
     * Envia ao serviço de destino (EXECUCAO ou BILLING) a mensagem já gravada na outbox,
     * usando o id da linha como messageId do envelope.
     */
    void publicar(MensagemOutbox mensagem);
}
