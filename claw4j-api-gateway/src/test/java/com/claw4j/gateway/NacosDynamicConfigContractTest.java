package com.claw4j.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Gateway dynamic configuration through runtime model-stream governance settings.
 */
class NacosDynamicConfigContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path BOOTSTRAP_YML_PATH = Path.of("src/main/resources/bootstrap.yml");
    private static final Path BOOTSTRAP_PROPERTIES_PATH = Path.of("src/main/resources/bootstrap.properties");
    private static final String SHARED_DATA_ID = "claw4j-shared.yaml";
    private static final String SERVICE_DATA_ID_EXPRESSION =
            "optional:nacos:${spring.application.name}.yaml?group=CLAW4J_DEV_GROUP&refreshEnabled=true";

    @Test
    void moduleDeclaresNacosConfigDependencyWithoutSiblingServices() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-tool-executor</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationConfigurationImportsOptionalYamlNacosConfigWithSafeDefaults() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("optional:nacos:" + SHARED_DATA_ID + "?group=CLAW4J_DEV_GROUP&refreshEnabled=true");
        assertThat(applicationYaml).contains(SERVICE_DATA_ID_EXPRESSION);
        assertThat(applicationYaml.indexOf(SHARED_DATA_ID))
                .isLessThan(applicationYaml.indexOf("${spring.application.name}.yaml"));
        assertThat(applicationYaml).contains("server-addr: ${CLAW4J_NACOS_SERVER_ADDR:127.0.0.1:8848}");
        assertThat(applicationYaml).contains("namespace: public");
        assertThat(applicationYaml).contains("group: CLAW4J_DEV_GROUP");
        assertThat(applicationYaml).contains("prefix: ${spring.application.name}");
        assertThat(applicationYaml).contains("file-extension: yaml");
        assertThat(applicationYaml).contains("refresh-enabled: true");
        assertThat(applicationYaml).contains("timeout: 3000");
        assertThat(applicationYaml).doesNotContain("CLAW4J_NACOS_CONFIG_");
        assertThat(Files.exists(BOOTSTRAP_YML_PATH)).isFalse();
        assertThat(Files.exists(BOOTSTRAP_PROPERTIES_PATH)).isFalse();
    }

    @Test
    void runtimeConfigKeepsModelStreamPathWithoutDemoOrSentinelRuleSources() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("sentinel:");
        assertThat(applicationYaml).contains("dashboard: ${CLAW4J_SENTINEL_DASHBOARD:127.0.0.1:8858}");
        assertThat(applicationYaml).doesNotContain("datasource:");
        assertThat(applicationYaml).doesNotContain("gateway-flow-rules:");
        assertThat(applicationYaml).doesNotContain("gateway-param-flow-rules:");
        assertThat(applicationYaml).doesNotContain("claw4j.sentinel.gateway");
        assertThat(applicationYaml).doesNotContain("/internal/gateway/config/dynamic");
        assertThat(applicationYaml).doesNotContain("smoke-rate-limit-threshold");
        assertThat(applicationYaml).doesNotContain("demo-message");
    }

    @Test
    void deletedDynamicConfigProofEndpointDoesNotRemainInProductionSources() throws IOException {
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/config/GatewayDynamicConfigProperties.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/controller/GatewayDynamicConfigController.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/service/GatewayDynamicConfigService.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/dto/DynamicConfigStatus.java")))
                .isFalse();
        assertThat(Files.readString(Path.of("src/main/java/com/claw4j/gateway/controller/GatewayStreamingModelController.java")))
                .doesNotContain("/internal/gateway/config/dynamic");
    }
}
