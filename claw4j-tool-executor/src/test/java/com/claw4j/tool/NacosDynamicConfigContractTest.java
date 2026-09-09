package com.claw4j.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Tool Executor's Nacos dynamic configuration contract.
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
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationConfigurationImportsOptionalNacosConfigWithSafeDefaults() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("import:");
        assertThat(applicationYaml).contains("optional:nacos:" + SHARED_DATA_ID + "?group=CLAW4J_DEV_GROUP&refreshEnabled=true");
        assertThat(applicationYaml).contains(
                "optional:nacos:${spring.application.name}.yaml?group=CLAW4J_DEV_GROUP&refreshEnabled=true"
        );
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
}
