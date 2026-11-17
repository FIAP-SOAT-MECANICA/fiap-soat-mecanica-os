package br.com.fiap.soat.mecanica.adapters.in.web.ordemServico;

import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto.HistoricoOrdemServicoResponse;
import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto.OrdemServicoIncluirRequest;
import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto.SagaOrdemServicoResponse;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.BuscarHistoricoOrdemServicoUseCase;
import br.com.fiap.soat.mecanica.application.saga.OrquestradorSaga;
import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto.OrdemServicoResponse;
import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.dto.PaginaResponse;
import br.com.fiap.soat.mecanica.adapters.in.web.ordemServico.mapper.OrdemServicoResponseMapper;
import br.com.fiap.soat.mecanica.application.ordemServico.dto.Pagina;
import br.com.fiap.soat.mecanica.application.ordemServico.dto.Paginacao;
import br.com.fiap.soat.mecanica.application.ordemServico.usecase.*;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("ordem-servicos")
@Validated
public class OrdemServicoController {

    private final CadastrarOrdemServicoUseCase cadastrarOrdemServicoUseCase;
    private final BuscarOrdemServicoPorIdUseCase buscarOrdemServicoPorIdUseCase;
    private final CancelarOrdemServicoPorDesistenciaUseCase cancelarOrdemServicoPorDesistenciaUseCase;
    private final BuscarTodosOrdemServicoPorVeiculoPlacaUseCase buscarTodosOrdemServicoPorVeiculoPlacaUseCase;
    private final ListarOrdensServicoAtivasUseCase listarOrdensServicoAtivasUseCase;
    private final BuscarHistoricoOrdemServicoUseCase buscarHistoricoOrdemServicoUseCase;
    private final OrquestradorSaga orquestradorSaga;

    @PostMapping
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Cadastrar uma Ordem de Serviço")
    public ResponseEntity<OrdemServicoResponse> cadastrar(@Valid @RequestBody OrdemServicoIncluirRequest request) {

        OrdemServico os = cadastrarOrdemServicoUseCase.executar(request.observacao(), request.veiculoId());

        return ResponseEntity.ok(OrdemServicoResponseMapper.toResponse(os));
    }

    @GetMapping
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Listar ordens de servico ativas por prioridade")
    public ResponseEntity<PaginaResponse<OrdemServicoResponse>> listarAtivas(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        Pagina<OrdemServico> resultado = listarOrdensServicoAtivasUseCase.executar(new Paginacao(page, size));

        List<OrdemServicoResponse> content = resultado.content().stream()
                .map(OrdemServicoResponseMapper::toResponse)
                .toList();

        return ResponseEntity.ok(new PaginaResponse<>(
                content,
                resultado.page(),
                resultado.size(),
                resultado.totalElements(),
                resultado.totalPages(),
                resultado.first(),
                resultado.last()
        ));
    }

    @PatchMapping("/{id}/cancelar")
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Cancelar ordem de serviço")
    public ResponseEntity<OrdemServicoResponse> cancelar(@PathVariable UUID id) {
        OrdemServico os = cancelarOrdemServicoPorDesistenciaUseCase.executar(id);
        return ResponseEntity.ok(OrdemServicoResponseMapper.toResponse(os));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Buscar uma Ordem de Serviço por ID")
    public ResponseEntity<OrdemServicoResponse> buscarPorId(@PathVariable UUID id) {

        OrdemServico os = buscarOrdemServicoPorIdUseCase.executar(id);

        return ResponseEntity.ok(OrdemServicoResponseMapper.toResponse(os));
    }

    @GetMapping("/{id}/historico")
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Histórico de situações da Ordem de Serviço, em ordem cronológica")
    public ResponseEntity<List<HistoricoOrdemServicoResponse>> historico(@PathVariable UUID id) {
        return ResponseEntity.ok(buscarHistoricoOrdemServicoUseCase.executar(id).stream()
                .map(HistoricoOrdemServicoResponse::de)
                .toList());
    }

    @GetMapping("/{id}/saga")
    @PreAuthorize("hasRole('MECANICO')")
    @Operation(summary = "Estado da saga da Ordem de Serviço: passo atual, concluídos e compensados")
    public ResponseEntity<SagaOrdemServicoResponse> saga(@PathVariable UUID id) {
        return ResponseEntity.ok(SagaOrdemServicoResponse.de(orquestradorSaga.buscarPorOsId(id)));
    }

    @GetMapping("/veiculo/placa/{placa}")
    @Operation(summary = "Buscar Ordem de serviços por placa do veículo")
    public ResponseEntity<List<OrdemServicoResponse>> buscarTodosPorPlaca(
            @PathVariable String placa) {

        List<OrdemServico> ordemServicos = buscarTodosOrdemServicoPorVeiculoPlacaUseCase.executar(placa);

        List<OrdemServicoResponse> response = ordemServicos.stream()
                .map(OrdemServicoResponseMapper::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }
}
