package br.com.fiap.soat.mecanica.application.saga;

import java.util.Set;

public interface TratadorDeEventos {

    // Os "type" do envelope que este tratador sabe tratar, ex.: "PecasReservadas".
    Set<String> tipos();

    // Aplica o efeito do evento. Lançar exceção = falhou, a mensagem não é apagada.
    void tratar(EventoRecebido evento);
}
