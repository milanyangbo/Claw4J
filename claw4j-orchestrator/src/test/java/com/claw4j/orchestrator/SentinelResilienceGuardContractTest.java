package com.claw4j.orchestrator;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.config.OrchestratorSentinelProperties;
import com.claw4j.orchestrator.controller.OrchestratorSentinelGuardController;
import com.claw4j.orchestrator.dto.AgentCallGuardStatus;
import com.claw4j.orchestrator.service.AgentCallGuardService;
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
 * Verifies Orchestrator Sentinel resilience guard contracts.
 */
class SentinelResilienceGuardContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final List<Path> SENTINEL_SOURCE_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorSentinelProperties.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/dto/AgentCallGuardStatus.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/service/AgentCallGuardService.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/controller/OrchestratorSentinelGuardController.java")
    );
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final String DEFAULT_RESOURCE_NAME = "claw4j-orchestrator-agent-call";
    private static final String REQUIRED_TENANT_ID = "tenant-a";
    private static final String REQUIRED_USER_ID = "user-a";
    private static final double DEFAULT_ERROR_RATIO_THRESHOLD = 0.5D;
    private static final int DEFAULT_MINIMUM_REQUEST_AMOUNT = 2;
    private static final int DEFAULT_STAT_INTERVAL_MS = 1000;
    private static final int DEFAULT_RECOVERY_WINDOW_SECONDS = 2;
    private static final int PROBE_ATTEMPTS = 5;
    private static final long RECOVERY_WAIT_MS = 2200L;

    @AfterEach
    void resetSentinelRules() {
        DegradeRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    void moduleDeclaresSentinelRuntimeDependenciesOnlyForOrchestrator() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>");
        assertThat(pom).contains("<artifactId>sentinel-datasource-nacos</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-api-gateway</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-tool-executor</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationConfigurationExposesDashboardAndNacosSentinelRules() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("eager: ${CLAW4J_SENTINEL_EAGER:true}");
        assertThat(applicationYaml).contains("dashboard: ${CLAW4J_SENTINEL_DASHBOARD:127.0.0.1:8858}");
        assertThat(applicationYaml).contains("port: ${CLAW4J_SENTINEL_ORCHESTRATOR_TRANSPORT_PORT:8720}");
        assertThat(applicationYaml).contains("orchestrator-degrade-rules:");
        assertThat(applicationYaml).contains("group-id: ${CLAW4J_SENTINEL_RULE_GROUP:${CLAW4J_NACOS_CONFIG_GROUP:${CLAW4J_NACOS_GROUP:CLAW4J_DEV_GROUP}}}");
        assertThat(applicationYaml).contains("data-id: ${CLAW4J_SENTINEL_ORCHESTRATOR_DEGRADE_DATA_ID:claw4j-orchestrator-sentinel-degrade-rules.json}");
        assertThat(applicationYaml).contains("data-type: json");
        assertThat(applicationYaml).contains("rule-type: degrade");
        assertThat(applicationYaml).contains("agent-call-resource-name: ${CLAW4J_SENTINEL_ORCHESTRATOR_AGENT_CALL_RESOURCE_NAME:claw4j-orchestrator-agent-call}");
        assertThat(applicationYaml).contains("error-ratio-threshold: ${CLAW4J_SENTINEL_ORCHESTRATOR_ERROR_RATIO_THRESHOLD:0.5}");
        assertThat(applicationYaml).contains("minimum-request-amount: ${CLAW4J_SENTINEL_ORCHESTRATOR_MIN_REQUEST_AMOUNT:2}");
        assertThat(applicationYaml).contains("stat-interval-ms: ${CLAW4J_SENTINEL_ORCHESTRATOR_STAT_INTERVAL_MS:1000}");
        assertThat(applicationYaml).contains("recovery-window-seconds: ${CLAW4J_SENTINEL_ORCHESTRATOR_RECOVERY_WINDOW_SECONDS:2}");
    }

    @Test
    void orchestratorPropertiesAreConfigurationPropertiesAndNormalizeInvalidValues() {
        ConfigurationProperties configurationProperties = OrchestratorSentinelProperties.class
                .getAnnotation(ConfigurationProperties.class);
        OrchestratorSentinelProperties properties = new OrchestratorSentinelProperties();

        properties.setAgentCallResourceName(" ");
        properties.setErrorRatioThreshold(2.0D);
        properties.setMinimumRequestAmount(0);
        properties.setStatIntervalMs(-1);
        properties.setRecoveryWindowSeconds(0);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("claw4j.sentinel.orchestrator");
        assertThat(properties.getAgentCallResourceName()).isEqualTo(DEFAULT_RESOURCE_NAME);
        assertThat(properties.getErrorRatioThreshold()).isEqualTo(DEFAULT_ERROR_RATIO_THRESHOLD);
        assertThat(properties.getMinimumRequestAmount()).isEqualTo(DEFAULT_MINIMUM_REQUEST_AMOUNT);
        assertThat(properties.getStatIntervalMs()).isEqualTo(DEFAULT_STAT_INTERVAL_MS);
        assertThat(properties.getRecoveryWindowSeconds()).isEqualTo(DEFAULT_RECOVERY_WINDOW_SECONDS);
    }

    @Test
    void agentCallStatusDtoRequiresTraceableContext() {
        AgentCallGuardStatus status = AgentCallGuardStatus.success(
                DEFAULT_RESOURCE_NAME,
                "req-dto",
                REQUIRED_TENANT_ID
        );

        assertThat(status.getResourceName()).isEqualTo(DEFAULT_RESOURCE_NAME);
        assertThat(status.getRequestId()).isEqualTo("req-dto");
        assertThat(status.getTenantId()).isEqualTo(REQUIRED_TENANT_ID);
        assertThat(status.isFallbackApplied()).isFalse();
        assertThat(status.getResultMessage()).isEqualTo("agent-call-success");
        assertBusinessError(
                () -> AgentCallGuardStatus.success(DEFAULT_RESOURCE_NAME, " ", REQUIRED_TENANT_ID),
                ErrorCode.INVALID_REQUEST
        );
    }

    @Test
    void serviceReturnsSuccessWhenCircuitIsClosed() {
        AgentCallGuardService service = service(uniqueResourceName("success"), DEFAULT_RECOVERY_WINDOW_SECONDS);

        ApiResponse<AgentCallGuardStatus> response = guardedCall(service, "req-success", false);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().isFallbackApplied()).isFalse();
        assertThat(response.getData().getResultMessage()).isEqualTo("agent-call-success");
        assertThat(service.getProtectedExecutionCount()).isEqualTo(1);
    }

    @Test
    void invalidContextIsRejectedBeforeCircuitFallback() {
        AgentCallGuardService service = service(uniqueResourceName("invalid-context"), DEFAULT_RECOVERY_WINDOW_SECONDS);

        assertBusinessError(
                () -> service.guardedAgentCall("req-invalid", " ", REQUIRED_USER_ID, "idem-invalid", false),
                ErrorCode.INVALID_REQUEST
        );

        assertThat(service.getProtectedExecutionCount()).isZero();
    }

    @Test
    void highErrorRatioOpensCircuitAndShortCircuitsProtectedLogic() {
        AgentCallGuardService service = service(uniqueResourceName("open-circuit"), DEFAULT_RECOVERY_WINDOW_SECONDS);

        triggerFailure(service, "req-open-circuit-1");
        triggerFailure(service, "req-open-circuit-2");
        int executionCountBeforeFallback = service.getProtectedExecutionCount();
        ApiResponse<AgentCallGuardStatus> fallback = probeForFallback(service, "req-open-circuit-fallback");

        assertThat(fallback.isSuccess()).isTrue();
        assertThat(fallback.getData().isFallbackApplied()).isTrue();
        assertThat(fallback.getData().getResultMessage()).contains(ErrorCode.CIRCUIT_OPEN.getCode());
        assertThat(service.getProtectedExecutionCount()).isEqualTo(executionCountBeforeFallback);
    }

    @Test
    void circuitAllowsSuccessfulProbeAfterRecoveryWindow() throws InterruptedException {
        AgentCallGuardService service = service(uniqueResourceName("recover-circuit"), 1);

        triggerFailure(service, "req-recover-circuit-1");
        triggerFailure(service, "req-recover-circuit-2");
        ApiResponse<AgentCallGuardStatus> fallback = probeForFallback(service, "req-recover-circuit-fallback");
        Thread.sleep(RECOVERY_WAIT_MS);
        ApiResponse<AgentCallGuardStatus> recovered = guardedCall(service, "req-recovered-circuit", false);

        assertThat(fallback.getData().isFallbackApplied()).isTrue();
        assertThat(recovered.getData().isFallbackApplied()).isFalse();
        assertThat(recovered.getData().getResultMessage()).isEqualTo("agent-call-success");
    }

    @Test
    void controllerOnlyDelegatesHeadersAndFailureFlagToGuardService() {
        AgentCallGuardService service = service(uniqueResourceName("controller"), DEFAULT_RECOVERY_WINDOW_SECONDS);
        OrchestratorSentinelGuardController controller = new OrchestratorSentinelGuardController(service);

        ApiResponse<AgentCallGuardStatus> response = controller.getAgentCallStatus(
                "req-controller",
                REQUIRED_TENANT_ID,
                REQUIRED_USER_ID,
                "idem-controller",
                false
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

    private static AgentCallGuardService service(String resourceName, int recoveryWindowSeconds) {
        OrchestratorSentinelProperties properties = new OrchestratorSentinelProperties();
        properties.setAgentCallResourceName(resourceName);
        properties.setErrorRatioThreshold(DEFAULT_ERROR_RATIO_THRESHOLD);
        properties.setMinimumRequestAmount(DEFAULT_MINIMUM_REQUEST_AMOUNT);
        properties.setStatIntervalMs(DEFAULT_STAT_INTERVAL_MS);
        properties.setRecoveryWindowSeconds(recoveryWindowSeconds);
        AgentCallGuardService service = new AgentCallGuardService(properties);
        service.loadLocalRules();
        return service;
    }

    private static ApiResponse<AgentCallGuardStatus> guardedCall(
            AgentCallGuardService service,
            String requestId,
            boolean simulateFailure
    ) {
        return service.guardedAgentCall(
                requestId,
                REQUIRED_TENANT_ID,
                REQUIRED_USER_ID,
                "idem-" + requestId,
                simulateFailure
        );
    }

    private static void triggerFailure(AgentCallGuardService service, String requestId) {
        assertBusinessError(
                () -> guardedCall(service, requestId, true),
                ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE
        );
    }

    private static ApiResponse<AgentCallGuardStatus> probeForFallback(AgentCallGuardService service, String requestId) {
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            ApiResponse<AgentCallGuardStatus> response = guardedCall(service, requestId + "-" + attempt, false);
            if (response.getData().isFallbackApplied()) {
                return response;
            }
        }
        throw new AssertionError("Sentinel circuit did not open for " + requestId);
    }

    private static void assertBusinessError(Runnable invocation, ErrorCode expectedErrorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expectedErrorCode);
    }

    private static String uniqueResourceName(String suffix) {
        return "test-orchestrator-sentinel-" + suffix + "-" + System.nanoTime();
    }
}
