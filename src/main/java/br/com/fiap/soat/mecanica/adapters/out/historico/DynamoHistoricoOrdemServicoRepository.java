package br.com.fiap.soat.mecanica.adapters.out.historico;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.HistoricoOrdemServicoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tabela os-historico (infra/historico). PK osId, SK ocorridoEm = "<ISO UTC ms>#<messageId>":
 * o texto ISO de tamanho fixo ordena igual ao tempo, e o messageId evita que duas transições
 * no mesmo milissegundo se sobrescrevam. A gravação é condicional, então republicar a mesma
 * linha da outbox não duplica o histórico.
 */
@Repository
public class DynamoHistoricoOrdemServicoRepository implements HistoricoOrdemServicoRepository {

    private static final DateTimeFormatter ISO_MILIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbClient dynamoDbClient;
    private final String tabela;

    public DynamoHistoricoOrdemServicoRepository(DynamoDbClient dynamoDbClient,
                                                 @Value("${mecanica.historico.tabela:os-historico}") String tabela) {
        this.dynamoDbClient = dynamoDbClient;
        this.tabela = tabela;
    }

    @Override
    public void registrar(EntradaHistorico entrada) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("osId", texto(entrada.osId().toString()));
        item.put("ocorridoEm", texto(ISO_MILIS.format(entrada.ocorridoEm()) + "#" + entrada.messageId()));
        item.put("messageId", texto(entrada.messageId().toString()));
        item.put("situacaoNova", texto(entrada.situacaoNova()));
        if (entrada.situacaoAnterior() != null) {
            item.put("situacaoAnterior", texto(entrada.situacaoAnterior()));
        }
        if (entrada.evento() != null) {
            item.put("evento", texto(entrada.evento()));
        }

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tabela)
                    .item(item)
                    .conditionExpression("attribute_not_exists(ocorridoEm)")
                    .build());
        } catch (ConditionalCheckFailedException jaGravada) {
            // Mesma entrada republicada pela outbox: já está no histórico.
        }
    }

    @Override
    public List<EntradaHistorico> listarPorOsId(UUID osId) {
        QueryRequest consulta = QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("osId = :osId")
                .expressionAttributeValues(Map.of(":osId", texto(osId.toString())))
                .scanIndexForward(true)
                .build();

        return dynamoDbClient.queryPaginator(consulta).items().stream()
                .map(DynamoHistoricoOrdemServicoRepository::paraEntrada)
                .toList();
    }

    private static EntradaHistorico paraEntrada(Map<String, AttributeValue> item) {
        String chave = item.get("ocorridoEm").s();
        return new EntradaHistorico(
                UUID.fromString(item.get("osId").s()),
                Instant.parse(chave.substring(0, chave.indexOf('#'))),
                UUID.fromString(item.get("messageId").s()),
                valor(item, "situacaoAnterior"),
                valor(item, "situacaoNova"),
                valor(item, "evento")
        );
    }

    private static String valor(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item.get(nome);
        return valor == null ? null : valor.s();
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }
}
