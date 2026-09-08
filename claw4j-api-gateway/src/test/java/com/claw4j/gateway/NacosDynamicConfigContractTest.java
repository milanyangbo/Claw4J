package com.claw4j.gateway;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.config.GatewayDynamicConfigProperties;
import com.claw4j.gateway.controller.GatewayDynamicConfigController;
import com.claw4j.gateway.dto.DynamicConfigStatus;
import com.claw4j.gateway.service.GatewayDynamicConfigService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Gateway's Nacos dynamic configuration contract.
 */
class NacosDynamicConfigContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path BOOTSTRAP_YML_PATH = Path.of("src/main/resources/bootstrap.yml");
    private static final Path BOOTSTRAP_PROPERTIES_PATH = Path.of("src/main/resources/bootstrap.properties");
    private static final String SHARED_DATA_ID = "claw4j-shared.yaml";
    private static final String DEMO_MESSAGE = "gateway-demo";

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
        assertThat(applicationYaml).contains("smoke-rate-limit-threshold: ${CLAW4J_GATEWAY_DYNAMIC_CONFIG_SMOKE_RATE_LIMIT_THRESHOLD:10}");
        assertThat(applicationYaml).contains("demo-message: ${CLAW4J_GATEWAY_DYNAMIC_CONFIG_DEMO_MESSAGE:local-default}");
        assertThat(Files.exists(BOOTSTRAP_YML_PATH)).isFalse();
        assertThat(Files.exists(BOOTSTRAP_PROPERTIES_PATH)).isFalse();
    }

    @Test
    void gatewayConfigPropertiesUseRefreshScopedConfigurationProperties() {
        RefreshScope refreshScope = GatewayDynamicConfigProperties.class.getAnnotation(RefreshScope.class);
        ConfigurationProperties configurationProperties = GatewayDynamicConfigProperties.class
                .getAnnotation(ConfigurationProperties.class);
        GatewayDynamicConfigProperties properties = properties("25");

        assertThat(refreshScope).isNotNull();
        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("claw4j.dynamic-config.gateway");
        assertThat(properties.getSmokeRateLimitThreshold()).isEqualTo("25");
        assertThat(properties.getDemoMessage()).isEqualTo(DEMO_MESSAGE);
    }

    @Test
    void serviceReturnsSimpleEffectiveStatus() {
        GatewayDynamicConfigService service = new GatewayDynamicConfigService(() -> properties("25"));

        DynamicConfigStatus status = service.currentStatus();

        assertThat(status.getEffectiveThreshold()).isEqualTo(25);
        assertThat(status.getDemoMessage()).isEqualTo(DEMO_MESSAGE);
        assertThat(status.isFallbackApplied()).isFalse();
        assertThat(status.getInvalidKey()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "10001"})
    void invalidThresholdFallsBackToLastValidValue(String invalidThreshold) {
        AtomicReference<GatewayDynamicConfigProperties> properties = new AtomicReference<>(properties("15"));
        GatewayDynamicConfigService service = new GatewayDynamicConfigService(properties::get);

        DynamicConfigStatus initialStatus = service.currentStatus();
        properties.set(properties(invalidThreshold));
        DynamicConfigStatus invalidStatus = service.currentStatus();

        assertThat(initialStatus.getEffectiveThreshold()).isEqualTo(15);
        assertThat(invalidStatus.getEffectiveThreshold()).isEqualTo(15);
        assertThat(invalidStatus.isFallbackApplied()).isTrue();
        assertThat(invalidStatus.getInvalidKey())
                .isEqualTo("claw4j.dynamic-config.gateway.smoke-rate-limit-threshold");
        assertThat(invalidStatus.getDemoMessage()).isEqualTo(DEMO_MESSAGE);
    }

    @Test
    void invalidThresholdFallsBackToLocalDefaultWhenNoLastValidValueExists() {
        GatewayDynamicConfigService service = new GatewayDynamicConfigService(() -> properties(" "));

        DynamicConfigStatus status = service.currentStatus();

        assertThat(status.getEffectiveThreshold()).isEqualTo(10);
        assertThat(status.isFallbackApplied()).isTrue();
        assertThat(status.getInvalidKey()).isEqualTo("claw4j.dynamic-config.gateway.smoke-rate-limit-threshold");
    }

    @Test
    void statusDtoRejectsInvalidEffectiveThreshold() {
        assertThatThrownBy(() -> DynamicConfigStatus.of(0, DEMO_MESSAGE, false, ""))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void controllerDelegatesToDynamicConfigService() {
        GatewayDynamicConfigController controller = new GatewayDynamicConfigController(
                new GatewayDynamicConfigService(() -> properties("12"))
        );

        ApiResponse<DynamicConfigStatus> response = controller.getDynamicConfigStatus();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getEffectiveThreshold()).isEqualTo(12);
    }

    private static GatewayDynamicConfigProperties properties(String threshold) {
        GatewayDynamicConfigProperties properties = new GatewayDynamicConfigProperties();
        properties.setSmokeRateLimitThreshold(threshold);
        properties.setDemoMessage(DEMO_MESSAGE);
        return properties;
    }
}
