package br.com.fiap.soat.mecanica.adapters.in.web.cliente.dto;

import br.com.fiap.soat.mecanica.domain.enums.StatusRecursoEnum;

import java.util.UUID;

public record ClienteSituacaoResponse(
        UUID id,
        StatusRecursoEnum status
) {}
