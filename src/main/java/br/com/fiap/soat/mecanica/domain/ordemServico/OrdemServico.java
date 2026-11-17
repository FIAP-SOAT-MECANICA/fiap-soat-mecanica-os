package br.com.fiap.soat.mecanica.domain.ordemServico;

import br.com.fiap.soat.mecanica.domain.Principal;
import br.com.fiap.soat.mecanica.domain.enums.SituacaoOrdemServicoEnum;
import br.com.fiap.soat.mecanica.domain.enums.StatusRecursoEnum;
import br.com.fiap.soat.mecanica.domain.exception.RegraNegocioException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrdemServico extends Principal {

    private UUID id;

    private SituacaoOrdemServicoEnum situacao;

    private LocalDateTime dataRecebida;
    private LocalDateTime dataDiagnostico;
    private LocalDateTime dataAguardandoAprovacao;
    private LocalDateTime dataExecucao;
    private LocalDateTime dataFinalizada;
    private LocalDateTime dataEntregue;
    private String observacao;
    private UUID veiculoId;
    private UUID usuarioId;

    public OrdemServico(String observacao, UUID veiculoId, UUID usuarioId) {
        validar(veiculoId, usuarioId);

        this.observacao = observacao;
        this.veiculoId = veiculoId;
        this.usuarioId = usuarioId;
        this.situacao = SituacaoOrdemServicoEnum.RECEBIDA;
        this.dataRecebida = LocalDateTime.now();
    }

    public static OrdemServico reconstruir(
            UUID id,
            StatusRecursoEnum status,
            SituacaoOrdemServicoEnum situacao,
            LocalDateTime dataRecebida,
            LocalDateTime dataDiagnostico,
            LocalDateTime dataAguardandoAprovacao,
            LocalDateTime dataExecucao,
            LocalDateTime dataFinalizada,
            LocalDateTime dataEntregue,
            String observacao,
            UUID veiculoId,
            UUID usuarioId
    ) {
        OrdemServico os = new OrdemServico();

        os.id = id;
        os.status = status;
        os.situacao = situacao;
        os.dataRecebida = dataRecebida;
        os.dataDiagnostico = dataDiagnostico;
        os.dataAguardandoAprovacao = dataAguardandoAprovacao;
        os.dataExecucao = dataExecucao;
        os.dataFinalizada = dataFinalizada;
        os.dataEntregue = dataEntregue;
        os.observacao = observacao;
        os.veiculoId = veiculoId;
        os.usuarioId = usuarioId;

        return os;
    }

    public void iniciarDiagnostico() {
        validarAtiva();

        if (!isRecebida()) {
            throw new RegraNegocioException("Ordem de serviço deve estar recebida para iniciar diagnóstico");
        }

        this.situacao = SituacaoOrdemServicoEnum.EM_DIAGNOSTICO;
        this.dataDiagnostico = LocalDateTime.now();
    }

    public void voltarParaDiagnostico() {
        validarAtiva();

        if (!isAguardandoAprovacao()) {
            throw new RegraNegocioException("Ordem de serviço deve estar aguardando aprovação para voltar ao diagnóstico");
        }

        this.situacao = SituacaoOrdemServicoEnum.EM_DIAGNOSTICO;
    }

    public void enviarParaAprovacao() {
        validarAtiva();

        if (!isEmDiagnostico()) {
            throw new RegraNegocioException("Ordem de serviço deve estar em diagnóstico para iniciar aguardando aprovação");
        }
        this.situacao = SituacaoOrdemServicoEnum.AGUARDANDO_APROVACAO;
        this.dataAguardandoAprovacao = LocalDateTime.now();
    }

    public void iniciarExecucao() {
        validarAtiva();

        if (!isAguardandoAprovacao()) {
            throw new RegraNegocioException("Ordem de serviço deve estar aguardando aprovação para iniciar execução");
        }

        this.situacao = SituacaoOrdemServicoEnum.EM_EXECUCAO;
        this.dataExecucao = LocalDateTime.now();
    }

    public void finalizar() {
        validarAtiva();

        if (!isEmExecucao()) {
            throw new RegraNegocioException("Ordem de serviço deve estar em execução para finalizar");
        }

        this.situacao = SituacaoOrdemServicoEnum.FINALIZADA;
        this.dataFinalizada = LocalDateTime.now();
    }

    public void entregar() {
        validarAtiva();

        if (!isFinalizada()) {
            throw new RegraNegocioException("Ordem de serviço deve estar como finalizada para entregar");
        }

        this.situacao = SituacaoOrdemServicoEnum.ENTREGUE;
        this.dataEntregue = LocalDateTime.now();
    }

    private void validar(UUID veiculoId, UUID usuarioId) {
        if (veiculoId == null) throw new RegraNegocioException("O veículo obrigatório");

        if (usuarioId == null) throw new RegraNegocioException("O mecânico obrigatório");
    }

    public boolean isRecebida() {
        return this.situacao == SituacaoOrdemServicoEnum.RECEBIDA;
    }

    public boolean isEmDiagnostico() {
        return this.situacao == SituacaoOrdemServicoEnum.EM_DIAGNOSTICO;
    }

    public boolean isAguardandoAprovacao() {
        return this.situacao == SituacaoOrdemServicoEnum.AGUARDANDO_APROVACAO;
    }

    public boolean isEmExecucao() {
        return this.situacao == SituacaoOrdemServicoEnum.EM_EXECUCAO;
    }

    public boolean isFinalizada() {
        return this.situacao == SituacaoOrdemServicoEnum.FINALIZADA;
    }

    private boolean isCancelada() {
        return this.situacao == SituacaoOrdemServicoEnum.CANCELADA;
    }

    private boolean isEntregue() {
        return this.situacao == SituacaoOrdemServicoEnum.ENTREGUE;
    }

    public void validarPermiteAlterarDiagnostico() {
        validarAtiva();

        if (!isEmDiagnostico()) {
            throw new RegraNegocioException("Ordem de serviço deve estar em diagnóstico");
        }
    }

    public void cancelarPorDesistencia() {
        validarAtiva();

        if (isCancelada()) {
            return;
        }

        if (isFinalizada()) {
            throw new RegraNegocioException("Ordem de serviço não pode ser cancelada, pois está finalizada");
        }

        if (isEntregue()) {
            throw new RegraNegocioException("Ordem de serviço não pode ser cancelada, pois está entregue");
        }

        this.situacao = SituacaoOrdemServicoEnum.CANCELADA;
    }

    public void validarAtiva() {
        if (this.isInativo()) {
            throw new RegraNegocioException("Ordem de serviço está inativa");
        }
    }
}
