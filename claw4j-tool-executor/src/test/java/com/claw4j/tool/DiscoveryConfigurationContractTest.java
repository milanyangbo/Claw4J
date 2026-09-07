package com.claw4j.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Tool Executor service discovery contract.
 */
class DiscoveryConfigurationContractTest {

    private static final String APPLICATION_NAME = "claw4j-tool-executor";
    private static final String PACKAGE_NAME = "com.claw4j.tool";
    private static final String SERVICE_PORT = "8082";
    private static final String SERVICE_ROLE = "tool-executor";
    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");

    @Test
    void moduleDeclaresDiscoveryDependenciesWithoutSiblingServices() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>");
        assertThat(pom).contains("<artifactId>spring-boot-starter-actuator</artifactId>");
        assertThat(pom).contains("<artifactId>claw4j-common</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-api-gateway</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationClassExplicitlyEnablesDiscovery() {
        assertThat(ToolExecutorApplication.class.getPackageName()).isEqualTo(PACKAGE_NAME);
        assertThat(ToolExecutorApplication.class.isAnnotationPresent(EnableDiscoveryClient.class)).isTrue();
    }

    @Test
    void applicationConfigurationExposesNacosDiscoveryMetadataAndHealth() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("name: " + APPLICATION_NAME);
        assertThat(applicationYaml).contains("port: " + SERVICE_PORT);
        assertThat(applicationYaml).contains("server-addr: ${CLAW4J_NACOS_SERVER_ADDR:127.0.0.1:8848}");
        assertThat(applicationYaml).contains("namespace: ${CLAW4J_NACOS_NAMESPACE:public}");
        assertThat(applicationYaml).contains("group: ${CLAW4J_NACOS_GROUP:CLAW4J_DEV_GROUP}");
        assertThat(applicationYaml).contains("module: " + APPLICATION_NAME);
        assertThat(applicationYaml).contains("role: " + SERVICE_ROLE);
        assertThat(applicationYaml).contains("environment: ${CLAW4J_ENVIRONMENT:local}");
        assertThat(applicationYaml).contains("include: health");
        assertThat(applicationYaml).contains("probes:");
        assertThat(applicationYaml).contains("enabled: true");
    }
}
