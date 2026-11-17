package br.com.fiap.soat.mecanica.application.saga;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Porta de entrada dos eventos consumidos. Inbox + efeito na mesma transação:
 * se o tratador falha, o registro na inbox é desfeito junto e a reentrega processa de novo.
 */
@Slf4j
@Service
public class ProcessadorDeEventos {

    private final InboxRepository inboxRepository;
    private final Map<String, TratadorDeEventos> tratadores = new HashMap<>();

    public ProcessadorDeEventos(InboxRepository inboxRepository, List<TratadorDeEventos> tratadores) {
        this.inboxRepository = inboxRepository;
        tratadores.forEach(tratador -> tratador.tipos().forEach(tipo -> {
            if (this.tratadores.putIfAbsent(tipo, tratador) != null) {
                throw new IllegalStateException("Mais de um tratador para o tipo " + tipo);
            }
        }));
    }

    @Transactional
    public ResultadoProcessamento processar(EventoRecebido evento) {
        TratadorDeEventos tratador = tratadores.get(evento.type());
        if (tratador == null) {
            return ResultadoProcessamento.SEM_TRATADOR;
        }

        if (!inboxRepository.registrarSeNova(evento.messageId(), evento.type())) {
            log.info("Evento duplicado ignorado. type={} messageId={}", evento.type(), evento.messageId());
            return ResultadoProcessamento.DUPLICADO;
        }

        tratador.tratar(evento);
        return ResultadoProcessamento.PROCESSADO;
    }
}
