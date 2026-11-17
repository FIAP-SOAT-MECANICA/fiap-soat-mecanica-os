package br.com.fiap.soat.mecanica.application.saga;

public enum ResultadoProcessamento {
    PROCESSADO,   // efeito aplicado: apagar da fila
    DUPLICADO,    // já processado antes: apagar da fila sem reaplicar
    SEM_TRATADOR  // ninguém trata este tipo: manter na fila (vai para a DLQ)
}
