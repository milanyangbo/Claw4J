package com.claw4j.gateway;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.config.GatewaySentinelProperties;
import com.claw4j.gateway.controller.GatewaySentinelGuardController;
import com.claw4j.gateway.dto.SentinelGuardStatus;
import com.claw4j.gateway.service.GatewaySentinelGuardService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Gateway Sentinel resilience guard contracts.
 */
class SentinelResilienceGuardContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final List<Path> SENTINEL_SOURCE_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/gateway/config/GatewaySentinelProperties.java"),
            Path.of("src/main/java/com/claw4j/gateway/dto/SentinelGuardStatus.java"),
            Path.of("src/main/java/com/claw4j/gateway/service/GatewaySentinelGuardService.java"),
            Path.of("src/main/java/com/claw4j/gateway/controller/GatewaySentinelGuardController.java")
    );
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final String DEFAULT_RESOURCE_NAME = "claw4j-gateway-ingress";
    private static final String REQUIRED_TENANT_ID = "tenant-a";
    private static final String OTHER_TENANT_ID = "tenant-b";
    private static final String REQUIRED_USER_ID = "user-a";
    private static final double DEFAULT_GLOBAL_QPS = 2.0D;
    private static final double DEFAULT_TENANT_QPS = 1.0D;
    private static final double BLOCKING_QPS = 0.5D;
    private static final double ALLOWING_QPS = 1000.0D;
    private static final int RATE_LIMIT_PROBE_ATTEMPTS = 8;

    @AfterEach
    void resetSentinelRules() {
        FlowRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    void moduleDeclaresSentinelRuntimeDependenciesOnlyForGateway() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>");
        assertThat(pom).contains("<artifactId>sentinel-datasource-nacos</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-tool-executor</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationConfigurationExposesDashboardAndNacosSentinelRules() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("eager: ${CLAW4J_SENTINEL_EAGER:true}");
        assertThat(applicationYaml).contains("dashboard: ${CLAW4J_SENTINEL_DASHBOARD:127.0.0.1:8858}");
        assertThat(applicationYaml).contains("port: ${CLAW4J_SENTINEL_GATEWAY_TRANSPORT_PORT:8719}");
        assertThat(applicationYaml).contains("gateway-flow-rules:");
        assertThat(applicationYaml).contains("gateway-param-flow-rules:");
        assertThat(applicationYaml).contains("group-id: ${CLAW4J_SENTINEL_RULE_GROUP:${CLAW4J_NACOS_CONFIG_GROUP:${CLAW4J_NACOS_GROUP:CLAW4J_DEV_GROUP}}}");
        assertThat(applicationYaml).contains("data-id: ${CLAW4J_SENTINEL_GATEWAY_FLOW_DATA_ID:claw4j-api-gateway-sentinel-flow-rules.json}");
        assertThat(applicationYaml).contains("data-id: ${CLAW4J_SENTINEL_GATEWAY_PARAM_FLOW_DATA_ID:claw4j-api-gateway-sentinel-param-flow-rules.json}");
        assertThat(applicationYaml).contains("data-type: json");
        assertThat(applicationYaml).contains("rule-type: flow");
        assertThat(applicationYaml).contains("rule-type: param-flow");
        assertThat(applicationYaml).contains("resource-name: ${CLAW4J_SENTINEL_GATEWAY_RESOURCE_NAME:claw4j-gateway-ingress}");
        assertThat(applicationYaml).contains("global-qps-threshold: ${CLAW4J_SENTINEL_GATEWAY_GLOBAL_QPS:2}");
        assertThat(applicationYaml).contains("tenant-qps-threshold: ${CLAW4J_SENTINEL_GATEWAY_TENANT_QPS:1}");
    }

    @Test
    void gatewayPropertiesAreConfigurationPropertiesAndNormalizeInvalidValues() {
        ConfigurationProperties configurationProperties = GatewaySentinelProperties.class
                .getAnnotation(ConfigurationProperties.class);
        GatewaySentinelProperties properties = new GatewaySentinelProperties();

        properties.setResourceName(" ");
        properties.setGlobalQpsThreshold(-1.0D);
        properties.setTenantQpsThreshold(0.0D);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("claw4j.sentinel.gateway");
        assertThat(properties.getResourceName()).isEqualTo(DEFAULT_RESOURCE_NAME);
        assertThat(properties.getGlobalQpsThreshold()).isEqualTo(DEFAULT_GLOBAL_QPS);
        assertThat(properties.getTenantQpsThreshold()).isEqualTo(DEFAULT_TENANT_QPS);
    }

    @Test
    void guardStatusDtoRequiresTraceableContext() {
        SentinelGuardStatus status = SentinelGuardStatus.allowed(
                DEFAULT_RESOURCE_NAME,
                REQUIRED_TENANT_ID,
                "req-dto",
                REQUIRED_USER_ID,
                "idem-dto"
        );

        assertThat(status.getResourceName()).isEqualTo(DEFAULT_RESOURCE_NAME);
        assertThat(status.getTenantId()).isEqualTo(REQUIRED_TENANT_ID);
        assertThat(status.getRequestId()).isEqualTo("req-dto");
        assertThat(status.getUserId()).isEqualTo(REQUIRED_USER_ID);
        assertThat(status.getIdempotencyKey()).isEqualTo("idem-dto");
        assertThat(status.getGuardResult()).isEqualTo("allowed");
        assertBusinessError(
                () -> SentinelGuardStatus.allowed(DEFAULT_RESOURCE_NAME, " ", "req-dto", REQUIRED_USER_ID, "idem-dto"),
                ErrorCode.INVALID_REQUEST
        );
    }

    @Test
    void serviceReturnsAllowedResponseBelowConfiguredLimits() {
        GatewaySentinelGuardService service = service(uniqueResourceName("below-limit"), ALLOWING_QPS, ALLOWING_QPS);

        ApiResponse<SentinelGuardStatus> response = guardedCall(service, "req-below-limit", REQUIRED_TENANT_ID);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getTenantId()).isEqualTo(REQUIRED_TENANT_ID);
        assertThat(response.getData().getGuardResult()).isEqualTo("allowed");
    }

    @Test
    void serviceMapsGlobalFlowBlocksToRateLimitError() {
        GatewaySentinelGuardService service = service(uniqueResourceName("global-block"), BLOCKING_QPS, ALLOWING_QPS);

        assertBusinessError(
                () -> guardedCall(service, "req-global-block", REQUIRED_TENANT_ID),
                ErrorCode.RATE_LIMITED
        );
    }

    @Test
    void serviceIsolatesTenantParamFlowQuota() {
        GatewaySentinelGuardService service = service(uniqueResourceName("tenant-block"), ALLOWING_QPS, DEFAULT_TENANT_QPS);

        guardedCall(service, "req-tenant-a-1", REQUIRED_TENANT_ID);
        assertThat(probeForRateLimit(service, REQUIRED_TENANT_ID)).isTrue();

        ApiResponse<SentinelGuardStatus> tenantBResponse = guardedCall(service, "req-tenant-b-1", OTHER_TENANT_ID);
        assertThat(tenantBResponse.isSuccess()).isTrue();
        assertThat(tenantBResponse.getData().getTenantId()).isEqualTo(OTHER_TENANT_ID);
    }

    @Test
    void missingTenantIsRejectedBeforeSentinelAccounting() {
        GatewaySentinelGuardService service = service(uniqueResourceName("missing-tenant"), DEFAULT_TENANT_QPS, DEFAULT_TENANT_QPS);

        assertBusinessError(
                () -> service.guardedStatus("req-missing-tenant", " ", REQUIRED_USER_ID, "idem-missing-tenant"),
                ErrorCode.INVALID_REQUEST
        );
        ApiResponse<SentinelGuardStatus> response = guardedCall(service, "req-after-missing-tenant", REQUIRED_TENANT_ID);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getTenantId()).isEqualTo(REQUIRED_TENANT_ID);
    }

    @Test
    void controllerOnlyDelegatesHeadersToGuardService() {
        GatewaySentinelGuardService service = service(uniqueResourceName("controller"), ALLOWING_QPS, ALLOWING_QPS);
        GatewaySentinelGuardController controller = new GatewaySentinelGuardController(service);

        ApiResponse<SentinelGuardStatus> response = controller.getGuardedStatus(
                "req-controller",
                REQUIRED_TENANT_ID,
                REQUIRED_USER_ID,
                "idem-controller"
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getRequestId()).isEqualTo("req-controller");
    }

    @Test
    void sentinelSourcesAvoidForbiddenPatterns() throws IOException {
        for (Path sourcePath : SENTINEL_SOURCE_PATHS) {
            String source = Files.readString(sourcePath);

            assertThat(source).doesNotContain("return null");
            assertThat(source).doesNotContain("System.out.println");
            assertThat(EMPTY_CATCH_PATTERN.matcher(source).find()).isFalse();
        }
    }

    private static GatewaySentinelGuardService service(
            String resourceName,
            double globalQpsThreshold,
            double tenantQpsThreshold
    ) {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();
        properties.setResourceName(resourceName);
        properties.setGlobalQpsThreshold(globalQpsThreshold);
        properties.setTenantQpsThreshold(tenantQpsThreshold);
        GatewaySentinelGuardService service = new GatewaySentinelGuardService(properties);
        service.loadLocalRules();
        return service;
    }

    private static ApiResponse<SentinelGuardStatus> guardedCall(
            GatewaySentinelGuardService service,
            String requestId,
            String tenantId
    ) {
        return service.guardedStatus(requestId, tenantId, REQUIRED_USER_ID, "idem-" + requestId);
    }

    private static boolean probeForRateLimit(GatewaySentinelGuardService service, String tenantId) {
        for (int attempt = 0; attempt < RATE_LIMIT_PROBE_ATTEMPTS; attempt++) {
            try {
                guardedCall(service, "req-rate-limit-probe-" + attempt, tenantId);
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.RATE_LIMITED) {
                    return true;
                }
                throw exception;
            }
        }
        return false;
    }

    private static void assertBusinessError(Runnable invocation, ErrorCode expectedErrorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expectedErrorCode);
    }

    private static String uniqueResourceName(String suffix) {
        return "test-gateway-sentinel-" + suffix + "-" + System.nanoTime();
    }
}
