package com.claw4j.a2a;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies A2A Broker's Nacos dynamic configuration contract.
 */
class NacosDynamicConfigContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path BOOTSTRAP_YML_PATH = Path.of("src/main/resources/bootstrap.yml");
    private static final Path BOOTSTRAP_PROPERTIES_PATH = Path.of("src/main/resources/bootstrap.properties");
    private static final String SHARED_DATA_ID = "claw4j-shared.yaml";

    @Test
    void moduleDeclaresNacosConfigDependencyWithoutSiblingServices() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-api-gateway</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-tool-executor</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
    }

    @Test
    void applicationConfigurationImportsOptionalNacosConfigWithSafeDefaults() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("optional:nacos:${CLAW4J_NACOS_CONFIG_SHARED_DATA_ID:" + SHARED_DATA_ID + "}");
        assertThat(applicationYaml).contains(
                "optional:nacos:${CLAW4J_NACOS_CONFIG_SERVICE_DATA_ID:${CLAW4J_NACOS_CONFIG_PREFIX:${spring.application.name}}.${CLAW4J_NACOS_CONFIG_FILE_EXTENSION:yaml}}"
        );
        assertThat(applicationYaml.indexOf("CLAW4J_NACOS_CONFIG_SHARED_DATA_ID"))
                .isLessThan(applicationYaml.indexOf("CLAW4J_NACOS_CONFIG_SERVICE_DATA_ID"));
        assertThat(applicationYaml).contains("server-addr: ${CLAW4J_NACOS_CONFIG_SERVER_ADDR:${CLAW4J_NACOS_SERVER_ADDR:127.0.0.1:8848}}");
        assertThat(applicationYaml).contains("namespace: ${CLAW4J_NACOS_CONFIG_NAMESPACE:${CLAW4J_NACOS_NAMESPACE:public}}");
        assertThat(applicationYaml).contains("group: ${CLAW4J_NACOS_CONFIG_GROUP:${CLAW4J_NACOS_GROUP:CLAW4J_DEV_GROUP}}");
        assertThat(applicationYaml).contains("prefix: ${CLAW4J_NACOS_CONFIG_PREFIX:${spring.application.name}}");
        assertThat(applicationYaml).contains("file-extension: ${CLAW4J_NACOS_CONFIG_FILE_EXTENSION:yaml}");
        assertThat(applicationYaml).contains("refresh-enabled: ${CLAW4J_NACOS_CONFIG_REFRESH_ENABLED:true}");
        assertThat(applicationYaml).contains("timeout: ${CLAW4J_NACOS_CONFIG_TIMEOUT_MS:3000}");
        assertThat(applicationYaml).contains("shared-data-id: ${CLAW4J_NACOS_CONFIG_SHARED_DATA_ID:" + SHARED_DATA_ID + "}");
        assertThat(applicationYaml).contains("service-data-id: ${CLAW4J_NACOS_CONFIG_SERVICE_DATA_ID:${CLAW4J_NACOS_CONFIG_PREFIX:${spring.application.name}}.${CLAW4J_NACOS_CONFIG_FILE_EXTENSION:yaml}}");
        assertThat(Files.exists(BOOTSTRAP_YML_PATH)).isFalse();
        assertThat(Files.exists(BOOTSTRAP_PROPERTIES_PATH)).isFalse();
    }
}
