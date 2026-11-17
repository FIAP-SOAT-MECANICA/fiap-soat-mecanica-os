package br.com.fiap.soat.mecanica.bdd;

import br.com.fiap.soat.mecanica.application.ordemServico.historico.EntradaHistorico;
import br.com.fiap.soat.mecanica.application.ordemServico.historico.HistoricoOrdemServicoRepository;
import br.com.fiap.soat.mecanica.application.security.CurrentUserPort;
import br.com.fiap.soat.mecanica.domain.usuario.UsuarioRepository;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Contexto Spring completo contra um Postgres real. Fora do Spring ficam só as bordas AWS:
 * o histórico usa um repositório em memória no lugar do DynamoDB, e os comandos são conferidos
 * na outbox. O consumidor SQS e o job da outbox ficam desligados (padrão das propriedades).
 *
 * O container é iniciado à mão: o @Testcontainers/@Container é extensão do JUnit Jupiter e não
 * roda dentro do motor do Cucumber.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = {br.com.fiap.soat.mecanica.FiapSoatMecanicaApiApplication.class,
        CucumberSpringConfiguration.BordasDeTeste.class})
@ActiveProfiles("test")
public class CucumberSpringConfiguration {

    static final UUID MECANICO_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("mecanica_bdd")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class BordasDeTeste {

        /** O mecânico do V9__mock_dados abre as OS. */
        @Bean
        @Primary
        CurrentUserPort mecanicoLogado(UsuarioRepository usuarioRepository) {
            return () -> usuarioRepository.buscarPorId(MECANICO_ID).orElseThrow();
        }

        @Bean
        @Primary
        HistoricoEmMemoria historicoEmMemoria() {
            return new HistoricoEmMemoria();
        }
    }

    public static class HistoricoEmMemoria implements HistoricoOrdemServicoRepository {
        private final List<EntradaHistorico> entradas = new CopyOnWriteArrayList<>();

        @Override
        public void registrar(EntradaHistorico entrada) {
            if (entradas.stream().noneMatch(e -> e.messageId().equals(entrada.messageId()))) {
                entradas.add(entrada);
            }
        }

        @Override
        public List<EntradaHistorico> listarPorOsId(UUID osId) {
            return entradas.stream()
                    .filter(e -> e.osId().equals(osId))
                    .sorted(Comparator.comparing(EntradaHistorico::ocorridoEm)
                            .thenComparing(e -> e.messageId().toString()))
                    .toList();
        }
    }
}
