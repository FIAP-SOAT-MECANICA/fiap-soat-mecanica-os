package br.com.fiap.soat.mecanica.bdd;

import br.com.fiap.soat.mecanica.adapters.out.messaging.PublicadorDaOutbox;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.BuscarHistoricoOrdemServicoUseCase;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.ordemServico.usecase.CadastrarOrdemServicoUseCase;
import br.com.fiap.soat.mecanica.application.ordemServico.usecase.CancelarOrdemServicoPorDesistenciaUseCase;
import br.com.fiap.soat.mecanica.application.saga.EventoForaDeOrdemException;
import br.com.fiap.soat.mecanica.application.saga.EventoRecebido;
import br.com.fiap.soat.mecanica.application.saga.MensagemOutbox;
import br.com.fiap.soat.mecanica.application.saga.OutboxRepository;
import br.com.fiap.soat.mecanica.application.saga.ProcessadorDeEventos;
import br.com.fiap.soat.mecanica.application.saga.ResultadoProcessamento;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServico;
import br.com.fiap.soat.mecanica.domain.ordemServico.OrdemServicoRepository;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServico;
import br.com.fiap.soat.mecanica.domain.saga.SagaOrdemServicoRepository;
import br.com.fiap.soat.mecanica.domain.veiculo.Veiculo;
import br.com.fiap.soat.mecanica.domain.veiculo.VeiculoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaOrdemServicoSteps {

    private static final UUID SERVICO_TROCA_DE_OLEO = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID PECA_FILTRO = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Autowired private CadastrarOrdemServicoUseCase cadastrarOrdemServico;
    @Autowired private CancelarOrdemServicoPorDesistenciaUseCase cancelarOrdemServico;
    @Autowired private BuscarHistoricoOrdemServicoUseCase buscarHistorico;
    @Autowired private ProcessadorDeEventos processador;
    @Autowired private OrdemServicoRepository ordemServicoRepository;
    @Autowired private SagaOrdemServicoRepository sagaRepository;
    @Autowired private VeiculoRepository veiculoRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private CucumberSpringConfiguration.HistoricoEmMemoria historico;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;

    private Veiculo veiculo;
    private UUID osId;
    private EventoRecebido ultimoEvento;
    private ResultadoProcessamento ultimoResultado;
    private final UUID orcamentoId = UUID.randomUUID();

    @Dado("um veículo cadastrado com a placa {string}")
    public void umVeiculoCadastrado(String placa) {
        veiculo = veiculoRepository.buscarPorPlaca(placa).orElseThrow();
    }

    @Quando("o mecânico abre uma ordem de serviço")
    public void oMecanicoAbreUmaOrdemDeServico() {
        osId = cadastrarOrdemServico.executar("barulho no motor", veiculo.getId()).getId();
    }

    @Quando("o Execução publica {string}")
    public void oExecucaoPublica(String tipo) {
        publicar(tipo);
    }

    @Quando("o Billing publica {string}")
    public void oBillingPublica(String tipo) {
        publicar(tipo);
    }

    @Quando("o mesmo evento é entregue de novo")
    public void oMesmoEventoEEntregueDeNovo() {
        ultimoResultado = processador.processar(ultimoEvento);
    }

    @Quando("o Execução publica {string} antes da hora")
    public void publicaAntesDaHora(String tipo) {
        ultimoEvento = evento(tipo);
    }

    @Quando("o cliente desiste da ordem de serviço")
    public void oClienteDesiste() {
        cancelarOrdemServico.executar(osId);
    }

    @Quando("o publicador da outbox roda")
    public void oPublicadorDaOutboxRoda() {
        List<MensagemOutbox> enviadas = new ArrayList<>();
        PublicadorDaOutbox publicador = new PublicadorDaOutbox(outboxRepository, enviadas::add, historico,
                new TransactionTemplate(transactionManager), 50);
        while (publicador.publicarLote() > 0) {
            // esvazia todas as pendentes, inclusive de cenários anteriores
        }
        assertThat(enviadas).isNotEmpty();
    }

    @Então("a OS está {string}")
    public void aOsEsta(String situacao) {
        OrdemServico os = ordemServicoRepository.buscarPorId(osId).orElseThrow();
        assertThat(os.getSituacao().name()).isEqualTo(situacao);
    }

    @Então("a saga está no passo {string} com estado {string}")
    public void aSagaEstaNoPasso(String passo, String estado) {
        SagaOrdemServico saga = saga();
        assertThat(saga.getPassoAtual().name()).isEqualTo(passo);
        assertThat(saga.getEstado().name()).isEqualTo(estado);
    }

    @Então("a saga concluiu os passos {string}")
    public void aSagaConcluiu(String passos) {
        assertThat(saga().getPassosConcluidos()).extracting(Enum::name).containsExactlyElementsOf(lista(passos));
    }

    @Então("a saga compensou os passos {string}")
    public void aSagaCompensou(String passos) {
        assertThat(saga().getPassosCompensados()).extracting(Enum::name).containsExactlyElementsOf(lista(passos));
    }

    @Então("o comando {string} foi gravado para {string}")
    public void oComandoFoiGravado(String tipo, String destino) {
        List<Map<String, Object>> linhas = jdbcClient.sql("""
                        SELECT payload::text AS payload, saga_id FROM outbox
                        WHERE os_id = :osId AND tipo = :tipo AND destino = :destino
                        """)
                .param("osId", osId).param("tipo", tipo).param("destino", destino)
                .query().listOfRows();
        assertThat(linhas).as("comando %s para %s", tipo, destino).hasSize(1);
        JsonNode payload = ler((String) linhas.get(0).get("payload"));
        assertThat(payload.get("osId").asText()).isEqualTo(osId.toString());
        assertThat(linhas.get(0).get("saga_id")).isEqualTo(saga().getSagaId());
        if ("ReservarPecas".equals(tipo)) {
            assertThat(payload.get("cycleId").asText()).isEqualTo(saga().getCicloReservaId().toString());
            assertThat(payload.get("pecas").get(0).get("qtd").asInt()).isEqualTo(2);
        }
    }

    @Então("a última entrega foi descartada como duplicada")
    public void foiDescartadaComoDuplicada() {
        assertThat(ultimoResultado).isEqualTo(ResultadoProcessamento.DUPLICADO);
    }

    @Então("o evento é recusado para nova tentativa")
    public void oEventoERecusado() {
        assertThatThrownBy(() -> processador.processar(ultimoEvento))
                .isInstanceOf(EventoForaDeOrdemException.class);
        Integer naInbox = jdbcClient.sql("SELECT count(*) FROM mensagem_processada WHERE message_id = :id")
                .param("id", ultimoEvento.messageId()).query(Integer.class).single();
        assertThat(naInbox).as("rollback desfaz a inbox para a reentrega processar").isZero();
    }

    @Então("nenhuma mensagem fica pendente na outbox")
    public void nenhumaPendente() {
        Integer pendentes = jdbcClient.sql("SELECT count(*) FROM outbox WHERE os_id = :osId AND publicado_em IS NULL")
                .param("osId", osId).query(Integer.class).single();
        assertThat(pendentes).isZero();
    }

    @Então("o histórico da OS mostra {string}")
    public void oHistoricoMostra(String situacoes) {
        assertThat(buscarHistorico.executar(osId)).extracting(EntradaHistorico::situacaoNova)
                .containsExactlyElementsOf(lista(situacoes));
    }

    // ---------------------------------------------------------------------------------

    private void publicar(String tipo) {
        ultimoEvento = evento(tipo);
        ultimoResultado = processador.processar(ultimoEvento);
        assertThat(ultimoResultado).isEqualTo(ResultadoProcessamento.PROCESSADO);
    }

    private EventoRecebido evento(String tipo) {
        // O Billing ainda manda sagaId nulo; o Execução manda o sagaId do comando.
        boolean doBilling = tipo.startsWith("Orcamento") || tipo.startsWith("Pagamento");
        UUID sagaId = doBilling ? null : saga().getSagaId();
        return new EventoRecebido(UUID.randomUUID(), tipo, sagaId, osId, "corr-bdd", null,
                objectMapper.valueToTree(payload(tipo)));
    }

    private Object payload(String tipo) {
        return switch (tipo) {
            case "DiagnosticoConcluido" -> Map.of("osId", osId, "itens", List.of(Map.of(
                    "servicoId", SERVICO_TROCA_DE_OLEO,
                    "precoMdo", 150.00,
                    "pecas", List.of(Map.of("pecaId", PECA_FILTRO, "qtd", 2, "valorUnit", 29.90)))));
            case "OrcamentoGerado", "OrcamentoAprovado", "OrcamentoRecusado" ->
                    Map.of("osId", osId, "orcamentoId", orcamentoId);
            case "ReservaPecasRecusada" -> Map.of("osId", osId,
                    "faltantes", List.of(Map.of("pecaId", PECA_FILTRO, "qtd", 2)));
            default -> Map.of("osId", osId);
        };
    }

    private SagaOrdemServico saga() {
        return sagaRepository.buscarPorOsId(osId).orElseThrow();
    }

    private JsonNode ler(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> lista(String valores) {
        return Arrays.stream(valores.split(",")).map(String::trim).toList();
    }
}
