package com.claw4j.gateway;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.service.GatewaySentinelGuardService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Gateway-only Sentinel protection for model streaming ingress.
 */
class SentinelResilienceGuardContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final List<Path> SENTINEL_SOURCE_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/gateway/service/GatewaySentinelGuardService.java"),
            Path.of("src/main/java/com/claw4j/gateway/service/OrchestratorGatewayService.java"),
            Path.of("src/main/java/com/claw4j/gateway/controller/GatewayStreamingModelController.java")
    );
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");
    private static final String REQUIRED_TENANT_ID = "tenant-a";
    private static final String OTHER_TENANT_ID = "tenant-b";
    private static final String REQUIRED_USER_ID = "user-a";
    private static final int TENANT_ARGUMENT_INDEX = 0;
    private static final Path SENTINEL_TEST_LOG_DIR = Path.of("target", "sentinel-test-logs").toAbsolutePath();
    private static final Path EAGLEEYE_TEST_LOG_DIR = Path.of("target", "eagleeye-test-logs").toAbsolutePath();
    private static final double DEFAULT_TENANT_QPS = 1.0D;
    private static final double BLOCKING_QPS = 0.5D;
    private static final int RATE_LIMIT_PROBE_ATTEMPTS = 8;
    private static final String FORBIDDEN_RETURN_NULL = "return " + "null";
    private static final String FORBIDDEN_SYSTEM_OUT = "System.out" + ".println";

    @BeforeAll
    static void configureWritableSentinelLogDirectory() throws IOException {
        Files.createDirectories(SENTINEL_TEST_LOG_DIR);
        Files.createDirectories(EAGLEEYE_TEST_LOG_DIR);
        System.setProperty("csp.sentinel.log.dir", SENTINEL_TEST_LOG_DIR.toString());
        System.setProperty("JM.LOG.PATH", EAGLEEYE_TEST_LOG_DIR.toString());
        System.setProperty("EAGLEEYE.LOG.PATH", EAGLEEYE_TEST_LOG_DIR.toString());
    }

    @AfterEach
    void resetSentinelRules() {
        FlowRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    void moduleDeclaresSentinelRuntimeDependenciesOnlyForGateway() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>");
        assertThat(pom).contains("<artifactId>spring-cloud-circuitbreaker-sentinel</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>sentinel-datasource-nacos</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-tool-executor</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-knowledge-memory</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void applicationConfigurationKeepsDefaultSentinelDashboardSetupOnly() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("eager: true");
        assertThat(applicationYaml).contains("dashboard: ${CLAW4J_SENTINEL_DASHBOARD:127.0.0.1:8858}");
        assertThat(applicationYaml).contains("port: 8719");
        assertThat(applicationYaml).doesNotContain("datasource:");
        assertThat(applicationYaml).doesNotContain("gateway-flow-rules:");
        assertThat(applicationYaml).doesNotContain("gateway-param-flow-rules:");
        assertThat(applicationYaml).doesNotContain("data-id: claw4j-api-gateway-sentinel-flow-rules.json");
        assertThat(applicationYaml).doesNotContain("data-id: claw4j-api-gateway-sentinel-param-flow-rules.json");
        assertThat(applicationYaml).doesNotContain("resource-name: claw4j-gateway-ingress");
        assertThat(applicationYaml).doesNotContain("global-qps-threshold");
        assertThat(applicationYaml).doesNotContain("tenant-qps-threshold");
        assertThat(applicationYaml).doesNotContain("claw4j.sentinel.gateway");
        assertThat(applicationYaml).doesNotContain("CLAW4J_SENTINEL_GATEWAY_");
        assertThat(applicationYaml).doesNotContain("CLAW4J_SENTINEL_RULE_GROUP");
    }

    @Test
    void serviceReturnsOperationResultBelowConfiguredLimits() {
        GatewaySentinelGuardService service = service();

        String response = guardedCall(service, "req-below-limit", REQUIRED_TENANT_ID);

        assertThat(response).isEqualTo("stream-opened");
    }

    @Test
    void serviceMapsGlobalFlowBlocksToRateLimitError() {
        GatewaySentinelGuardService service = service();
        loadGlobalQpsRule(BLOCKING_QPS);

        assertBusinessError(
                () -> guardedCall(service, "req-global-block", REQUIRED_TENANT_ID),
                ErrorCode.RATE_LIMITED
        );
    }

    @Test
    void serviceIsolatesTenantParamFlowQuota() {
        GatewaySentinelGuardService service = service();
        loadTenantQpsRule(DEFAULT_TENANT_QPS);

        guardedCall(service, "req-tenant-a-1", REQUIRED_TENANT_ID);
        assertThat(probeForRateLimit(service, REQUIRED_TENANT_ID)).isTrue();

        assertThat(guardedCall(service, "req-tenant-b-1", OTHER_TENANT_ID)).isEqualTo("stream-opened");
    }

    @Test
    void missingTenantIsRejectedBeforeSentinelAccounting() {
        GatewaySentinelGuardService service = service();
        AtomicInteger invocationCount = new AtomicInteger();

        assertBusinessError(
                () -> service.guardModelStream(
                        "req-missing-tenant",
                        " ",
                        REQUIRED_USER_ID,
                        "idem-missing-tenant",
                        () -> {
                            invocationCount.incrementAndGet();
                            return "should-not-run";
                        }
                ),
                ErrorCode.INVALID_REQUEST
        );

        assertThat(invocationCount).hasValue(0);
        assertThat(guardedCall(service, "req-after-missing-tenant", REQUIRED_TENANT_ID)).isEqualTo("stream-opened");
    }

    @Test
    void deletedGatewaySentinelProofEndpointDoesNotRemainInProductionSources() throws IOException {
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/controller/GatewaySentinelGuardController.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/gateway/dto/SentinelGuardStatus.java")))
                .isFalse();
        assertThat(Files.readString(Path.of("src/main/java/com/claw4j/gateway/controller/GatewayStreamingModelController.java")))
                .doesNotContain("/internal/gateway/sentinel/guarded");
    }

    @Test
    void sentinelSourcesAvoidForbiddenPatterns() throws IOException {
        for (Path sourcePath : SENTINEL_SOURCE_PATHS) {
            String source = Files.readString(sourcePath);

            assertThat(source).doesNotContain(FORBIDDEN_RETURN_NULL);
            assertThat(source).doesNotContain(FORBIDDEN_SYSTEM_OUT);
            assertThat(EMPTY_CATCH_PATTERN.matcher(source).find()).isFalse();
        }
    }

    private static GatewaySentinelGuardService service() {
        return new GatewaySentinelGuardService();
    }

    private static void loadGlobalQpsRule(double qpsThreshold) {
        FlowRule rule = new FlowRule(GatewaySentinelGuardService.MODEL_STREAM_RESOURCE_NAME);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qpsThreshold);
        FlowRuleManager.loadRules(List.of(rule));
    }

    private static void loadTenantQpsRule(double qpsThreshold) {
        ParamFlowRule rule = new ParamFlowRule(GatewaySentinelGuardService.MODEL_STREAM_RESOURCE_NAME);
        rule.setParamIdx(TENANT_ARGUMENT_INDEX);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qpsThreshold);
        ParamFlowRuleManager.loadRules(List.of(rule));
    }

    private static String guardedCall(
            GatewaySentinelGuardService service,
            String requestId,
            String tenantId
    ) {
        return service.guardModelStream(
                requestId,
                tenantId,
                REQUIRED_USER_ID,
                "idem-" + requestId,
                () -> "stream-opened"
        );
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

}
