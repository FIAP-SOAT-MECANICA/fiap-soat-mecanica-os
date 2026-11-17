package br.com.fiap.soat.mecanica.application.saga;

import java.util.UUID;

public interface InboxRepository {

    // true = mensagem nova, registrada agora; false = já tinha sido processada.
    boolean registrarSeNova(UUID messageId, String tipo);
}
