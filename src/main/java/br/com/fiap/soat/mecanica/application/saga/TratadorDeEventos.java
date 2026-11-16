package br.com.fiap.soat.mecanica.application.saga;

public interface TratadorDeEventos {

    // O "type" do envelope que este tratador sabe tratar, ex.: "PecasReservadas".
    String tipo();

    // Aplica o efeito do evento. Lançar exceção = falhou, a mensagem não é apagada.
    void tratar(EventoRecebido evento);
}