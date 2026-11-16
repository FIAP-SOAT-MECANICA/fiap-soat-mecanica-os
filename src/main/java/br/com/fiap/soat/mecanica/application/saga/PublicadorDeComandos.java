package br.com.fiap.soat.mecanica.application.saga;

import java.util.UUID;

public interface PublicadorDeComandos {

    /**
     * Envia o comando ao serviço de destino.
     *
     * @return o messageId gerado, que vira o causationId das respostas
     */
    UUID publicar(Comando comando);
}
