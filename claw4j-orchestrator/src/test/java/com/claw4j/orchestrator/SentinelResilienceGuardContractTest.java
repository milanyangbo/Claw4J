package com.claw4j.orchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Orchestrator no longer owns Sentinel runtime behavior.
 */
class SentinelResilienceGuardContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path GATEWAY_POM_PATH = Path.of("../claw4j-api-gateway/pom.xml");
    private static final Path GATEWAY_APPLICATION_YML_PATH = Path.of("../claw4j-api-gateway/src/main/resources/application.yml");
    private static final List<Path> DELETED_SENTINEL_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorSentinelProperties.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/dto/AgentCallGuardStatus.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/service/AgentCallGuardService.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/controller/OrchestratorSentinelGuardController.java")
    );

    @Test
    void moduleDoesNotDeclareSentinelRuntimeDependencies() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).doesNotContain("spring-cloud-starter-alibaba-sentinel");
        assertThat(pom).doesNotContain("sentinel-datasource-nacos");
        assertThat(pom).doesNotContain("spring-cloud-circuitbreaker-sentinel");
        assertThat(pom).contains("<artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>");
    }

    @Test
    void applicationConfigurationOmitsSentinelAndKeepsResilience4jModelGuard() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).doesNotContain("sentinel:");
        assertThat(applicationYaml).doesNotContain("CLAW4J_SENTINEL_ORCHESTRATOR");
        assertThat(applicationYaml).doesNotContain("orchestrator-degrade-rules");
        assertThat(applicationYaml).doesNotContain("agent-call-resource-name");
        assertThat(applicationYaml).contains("resilience4j:");
        assertThat(applicationYaml).contains("deepseek-primary-model:");
        assertThat(applicationYaml).contains("timeout-duration: 30s");
    }

    @Test
    void deletedOrchestratorSentinelProofClassesDoNotRemain() {
        for (Path deletedPath : DELETED_SENTINEL_PATHS) {
            assertThat(Files.exists(deletedPath)).isFalse();
        }
    }

    @Test
    void orchestratorProductionSourcesContainNoSentinelImportsOrProofTypes() throws IOException {
        try (var paths = Files.walk(Path.of("src/main/java/com/claw4j/orchestrator"))) {
            List<Path> sourcePaths = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            for (Path sourcePath : sourcePaths) {
                String source = Files.readString(sourcePath);
                assertThat(source).doesNotContain("com.alibaba.csp.sentinel");
                assertThat(source).doesNotContain("OrchestratorSentinel");
                assertThat(source).doesNotContain("AgentCallGuard");
            }
        }
    }

    @Test
    void sentinelCoverageRemainsGatewayOwned() throws IOException {
        String gatewayPom = Files.readString(GATEWAY_POM_PATH);
        String gatewayApplicationYaml = Files.readString(GATEWAY_APPLICATION_YML_PATH);

        assertThat(gatewayPom).contains("<artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>");
        assertThat(gatewayPom).contains("<artifactId>spring-cloud-circuitbreaker-sentinel</artifactId>");
        assertThat(gatewayPom).doesNotContain("<artifactId>sentinel-datasource-nacos</artifactId>");
        assertThat(gatewayApplicationYaml).contains("spring:");
        assertThat(gatewayApplicationYaml).contains("sentinel:");
        assertThat(gatewayApplicationYaml).contains("dashboard: ${CLAW4J_SENTINEL_DASHBOARD:127.0.0.1:8858}");
        assertThat(gatewayApplicationYaml).contains("port: 8719");
        assertThat(gatewayApplicationYaml).doesNotContain("datasource:");
        assertThat(gatewayApplicationYaml).doesNotContain("gateway-flow-rules:");
        assertThat(gatewayApplicationYaml).doesNotContain("gateway-param-flow-rules:");
    }
}
