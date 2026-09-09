package com.claw4j.orchestrator;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.client.ModelProviderClient;
import com.claw4j.orchestrator.client.SpringAiModelProviderClient;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.dto.ModelStreamEvent;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import com.claw4j.orchestrator.service.ModelContextAdapter;
import com.claw4j.orchestrator.service.ModelOutputParser;
import com.claw4j.orchestrator.service.ModelStreamClient;
import com.claw4j.orchestrator.service.ResilientModelStreamClient;
import com.claw4j.orchestrator.service.StreamingModelService;
import com.claw4j.orchestrator.service.StreamingResumePromptBuilder;
import com.claw4j.orchestrator.service.StreamingSessionStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Orchestrator model-provider failover and Resilience4j contracts.
 */
class ModelFailoverResilienceContractTest {

    private static final Path ORCHESTRATOR_POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path README_PATH = Path.of("../README.md");
    private static final Path SMOKE_TEST_PATH = Path.of("../MODEL_STREAMING_CHAIN_SMOKE_TESTS.md");
    private static final Path SPRING_AI_PROVIDER_CLIENT_PATH = Path.of(
            "src/main/java/com/claw4j/orchestrator/client/SpringAiModelProviderClient.java"
    );
    private static final List<Path> FAILOVER_SOURCE_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/orchestrator/client/ModelProviderClient.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/client/SpringAiModelProviderClient.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorStreamingModelProperties.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/service/ResilientModelStreamClient.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/service/StreamingModelService.java")
    );
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");
    private static final String PRIMARY_CIRCUIT_NAME = "deepseek-primary-model";
    private static final String REQUEST_ID = "req-failover";
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "user-a";
    private static final String IDEMPOTENCY_KEY = "idem-failover";
    private static final String PRIMARY_CONTENT = "deepseek answer";
    private static final String FALLBACK_CONTENT = "qwen answer";
    private static final String PARTIAL_PRIMARY_CONTENT = "deepseek partial ";
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(25);
    private static final Duration SHORT_OPEN_WAIT = Duration.ofMillis(80);
    private static final int TWO_CALLS = 2;
    private static final String FORMER_PROOF_CLIENT_KEY = "proof" + "-client";
    private static final String FORMER_DETERMINISTIC_CLIENT = "Deterministic" + "ModelStreamClient.java";
    private static final String FORBIDDEN_RETURN_NULL = "return " + "null";
    private static final String FORBIDDEN_SYSTEM_OUT = "System.out" + ".println";

    @Test
    void moduleDeclaresModelProviderAndResilienceDependenciesWithoutSentinel() throws IOException {
        String pom = Files.readString(ORCHESTRATOR_POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>");
        assertThat(pom).contains("<artifactId>spring-ai-starter-model-deepseek</artifactId>");
        assertThat(pom).contains("<artifactId>spring-ai-alibaba-starter-dashscope</artifactId>");
        assertThat(pom).doesNotContain("spring-cloud-starter-alibaba-sentinel");
        assertThat(pom).doesNotContain("sentinel-datasource-nacos");
        assertThat(pom).doesNotContain("spring-cloud-circuitbreaker-sentinel");
    }

    @Test
    void springAiProviderClientUsesDeepSeekAndDashScopeSdkAdapters() throws IOException {
        String source = Files.readString(SPRING_AI_PROVIDER_CLIENT_PATH);

        assertThat(source).contains("DeepSeekChatModel.builder()");
        assertThat(source).contains("DeepSeekApi.builder()");
        assertThat(source).contains("DashScopeChatModel.builder()");
        assertThat(source).contains("DashScopeApi.builder()");
        assertThat(source).contains("DeepSeekConnectionProperties");
        assertThat(source).contains("DashScopeConnectionProperties");
        assertThat(source).contains("deepSeekChatProperties.getOptions()");
        assertThat(source).contains("dashScopeChatProperties.getOptions()");
        assertThat(source).contains("ModelType.QWEN");
        assertThat(source).doesNotContain("getDeepseek()");
        assertThat(source).doesNotContain("getQwen()");
    }

    @Test
    void springAiProviderClientRequiresOfficialCredentialsWhenInvoked() {
        SpringAiModelProviderClient client = new SpringAiModelProviderClient(
                emptyProvider(),
                emptyProvider(),
                new DeepSeekConnectionProperties(),
                new DeepSeekChatProperties(),
                new DashScopeConnectionProperties(),
                new DashScopeChatProperties()
        );

        assertBusinessError(
                () -> client.stream(
                        ModelType.DEEPSEEK,
                        "prompt",
                        StreamingModelRequest.of("hello"),
                        initialContext("missing-credential"),
                        token -> {
                        }
                ),
                ErrorCode.MODEL_PROVIDER_CONFIGURATION_INVALID
        );
    }

    @Test
    void applicationConfigurationUsesOfficialProviderAndResilience4jSettings() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("model:");
        assertThat(applicationYaml).contains("chat: none");
        assertThat(applicationYaml).contains("deepseek:");
        assertThat(applicationYaml).contains("api-key: ${CLAW4J_DEEPSEEK_API_KEY:}");
        assertThat(applicationYaml).contains("model: deepseek-reasoner");
        assertThat(applicationYaml).contains("dashscope:");
        assertThat(applicationYaml).contains("api-key: ${CLAW4J_DASHSCOPE_API_KEY:}");
        assertThat(applicationYaml).contains("model: qwen-plus");
        assertThat(applicationYaml).contains("incremental-output: true");
        assertThat(applicationYaml).contains("fallback-model-type: qwen");
        assertThat(applicationYaml).contains("resilience4j:");
        assertThat(applicationYaml).contains("deepseek-primary-model:");
        assertThat(applicationYaml).contains("timeout-duration: 30s");
        assertThat(applicationYaml).doesNotContain(FORMER_PROOF_CLIENT_KEY);
        assertThat(applicationYaml).doesNotContain("CLAW4J_MODEL_CLIENT_MODE");
        assertThat(applicationYaml).doesNotContain("CLAW4J_DEEPSEEK_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("CLAW4J_QWEN_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("CLAW4J_MODEL_");
        assertThat(applicationYaml).doesNotContain("claw4j.model.client");
        assertThat(applicationYaml).doesNotContain("claw4j.model.resilience");
        assertThat(applicationYaml).doesNotContain("model-name: ${CLAW4J_DEEPSEEK_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("model-name: ${CLAW4J_QWEN_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("sk-");
    }

    @Test
    void primarySuccessDoesNotInvokeFallback() {
        RecordingProviderClient providerClient = RecordingProviderClient.primarySuccess(PRIMARY_CONTENT);
        StreamingModelService service = service(resilientClient(fastResilience(), providerClient));

        List<ModelStreamEvent> events = service.streamEvents(StreamingModelRequest.of("hello"), initialContext("success"));

        assertThat(tokenContent(events)).isEqualTo(PRIMARY_CONTENT);
        assertThat(eventNames(events)).doesNotContain(ModelStreamEvent.EVENT_FALLBACK_START);
        assertThat(providerClient.getPrimaryCalls()).isEqualTo(1);
        assertThat(providerClient.getFallbackCalls()).isZero();
    }

    @Test
    void primaryTimeoutUsesFallbackWithStableStatus() {
        RecordingProviderClient providerClient = RecordingProviderClient.primaryDelayThenFallback(
                Duration.ofMillis(150),
                FALLBACK_CONTENT
        );
        StreamingModelService service = service(resilientClient(fastResilience(), providerClient));

        List<ModelStreamEvent> events = service.streamEvents(
                StreamingModelRequest.of("timeout"),
                initialContext("timeout")
        );

        assertThat(tokenContent(events)).isEqualTo(FALLBACK_CONTENT);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getEventName()).isEqualTo(ModelStreamEvent.EVENT_FALLBACK_START);
            assertThat(event.getStatusCode()).isEqualTo(ModelStreamClient.STATUS_PRIMARY_TTFB_TIMEOUT);
        });
        assertThat(providerClient.getPrimaryCalls()).isEqualTo(1);
        assertThat(providerClient.getFallbackCalls()).isEqualTo(1);
    }

    @Test
    void providerFailureAfterPartialOutputResumesWithoutDuplicatePrefix() {
        RecordingProviderClient providerClient = RecordingProviderClient.partialThenFailure(
                PARTIAL_PRIMARY_CONTENT,
                PARTIAL_PRIMARY_CONTENT + FALLBACK_CONTENT
        );
        StreamingModelService service = service(resilientClient(fastResilience(), providerClient));

        List<ModelStreamEvent> events = service.streamEvents(
                StreamingModelRequest.of("partial"),
                initialContext("partial")
        );

        assertThat(tokenContent(events)).isEqualTo(PARTIAL_PRIMARY_CONTENT + FALLBACK_CONTENT);
        assertThat(providerClient.getFallbackPrompts()).anySatisfy(prompt -> {
            assertThat(prompt).contains("<user_query><![CDATA[partial]]></user_query>");
            assertThat(prompt).contains("<cached_output><![CDATA[" + PARTIAL_PRIMARY_CONTENT + "]]></cached_output>");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getEventName()).isEqualTo(ModelStreamEvent.EVENT_FALLBACK_START);
            assertThat(event.getStatusCode()).isEqualTo(ModelStreamClient.STATUS_PRIMARY_INTERRUPTED);
        });
    }

    @Test
    void errorRatioOpensPrimaryCircuitAndShortCircuitsToFallback() {
        RecordingProviderClient providerClient = RecordingProviderClient.primaryFailureThenFallback(FALLBACK_CONTENT);
        StreamingModelService service = service(resilientClient(circuitResilience(), providerClient));

        service.streamEvents(StreamingModelRequest.of("failure-1"), initialContext("failure-1"));
        service.streamEvents(StreamingModelRequest.of("failure-2"), initialContext("failure-2"));
        int callsBeforeOpenCircuit = providerClient.getPrimaryCalls();
        List<ModelStreamEvent> openCircuitEvents = service.streamEvents(
                StreamingModelRequest.of("open"),
                initialContext("open")
        );

        assertThat(providerClient.getPrimaryCalls()).isEqualTo(callsBeforeOpenCircuit);
        assertThat(providerClient.getFallbackCalls()).isEqualTo(3);
        assertThat(openCircuitEvents).anySatisfy(event -> {
            assertThat(event.getEventName()).isEqualTo(ModelStreamEvent.EVENT_FALLBACK_START);
            assertThat(event.getStatusCode()).isEqualTo(ModelStreamClient.STATUS_PRIMARY_CIRCUIT_OPEN);
        });
    }

    @Test
    void halfOpenSuccessfulProbeRestoresPrimaryRouting() throws InterruptedException {
        RecordingProviderClient providerClient = RecordingProviderClient.primaryFailureThenFallback(FALLBACK_CONTENT);
        StreamingModelService service = service(resilientClient(circuitResilience(), providerClient));

        service.streamEvents(StreamingModelRequest.of("failure-1"), initialContext("recover-1"));
        service.streamEvents(StreamingModelRequest.of("failure-2"), initialContext("recover-2"));
        service.streamEvents(StreamingModelRequest.of("open"), initialContext("recover-open"));
        providerClient.setPrimarySuccess(PRIMARY_CONTENT);
        Thread.sleep(SHORT_OPEN_WAIT.plusMillis(40L).toMillis());

        List<ModelStreamEvent> recoveredEvents = service.streamEvents(
                StreamingModelRequest.of("recovered"),
                initialContext("recovered")
        );

        assertThat(tokenContent(recoveredEvents)).isEqualTo(PRIMARY_CONTENT);
        assertThat(eventNames(recoveredEvents)).doesNotContain(ModelStreamEvent.EVENT_FALLBACK_START);
    }

    @Test
    void halfOpenFailedProbeReopensPrimaryCircuit() throws InterruptedException {
        RecordingProviderClient providerClient = RecordingProviderClient.primaryFailureThenFallback(FALLBACK_CONTENT);
        StreamingModelService service = service(resilientClient(circuitResilience(), providerClient));

        service.streamEvents(StreamingModelRequest.of("failure-1"), initialContext("failed-probe-1"));
        service.streamEvents(StreamingModelRequest.of("failure-2"), initialContext("failed-probe-2"));
        service.streamEvents(StreamingModelRequest.of("open"), initialContext("failed-probe-open"));
        Thread.sleep(SHORT_OPEN_WAIT.plusMillis(40L).toMillis());

        service.streamEvents(StreamingModelRequest.of("probe-fails"), initialContext("probe-fails"));
        int callsAfterFailedProbe = providerClient.getPrimaryCalls();
        List<ModelStreamEvent> reopenedEvents = service.streamEvents(
                StreamingModelRequest.of("still-open"),
                initialContext("still-open")
        );

        assertThat(providerClient.getPrimaryCalls()).isEqualTo(callsAfterFailedProbe);
        assertThat(reopenedEvents).anySatisfy(event -> {
            assertThat(event.getEventName()).isEqualTo(ModelStreamEvent.EVENT_FALLBACK_START);
            assertThat(event.getStatusCode()).isEqualTo(ModelStreamClient.STATUS_PRIMARY_CIRCUIT_OPEN);
        });
    }

    @Test
    void invalidContextIsRejectedBeforeProviderInvocation() {
        RecordingProviderClient providerClient = RecordingProviderClient.primarySuccess(PRIMARY_CONTENT);
        StreamingModelService service = service(resilientClient(fastResilience(), providerClient));

        assertBusinessError(
                () -> service.streamEvents(StreamingModelRequest.of("invalid"), initialContext(" ")),
                ErrorCode.INVALID_REQUEST
        );

        assertThat(providerClient.getPrimaryCalls()).isZero();
        assertThat(providerClient.getFallbackCalls()).isZero();
    }

    @Test
    void testDoublesStayOutOfProductionConfiguration() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/orchestrator/service/" + FORMER_DETERMINISTIC_CLIENT)))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorModelClientProperties.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorModelResilienceProperties.java")))
                .isFalse();
        assertThat(applicationYaml).doesNotContain("deterministic");
        assertThat(applicationYaml).doesNotContain(FORMER_PROOF_CLIENT_KEY);
    }

    @Test
    void documentationLinksIntegratedGatewayStreamingSmokeTests() throws IOException {
        String readme = Files.readString(README_PATH);
        String smokeTests = Files.readString(SMOKE_TEST_PATH);

        assertThat(readme).contains("MODEL_STREAMING_CHAIN_SMOKE_TESTS.md");
        assertThat(smokeTests).contains("http://127.0.0.1:8080/ai/chat?query=");
        assertThat(smokeTests).contains("CLAW4J_DEEPSEEK_API_KEY");
        assertThat(smokeTests).contains("CLAW4J_DASHSCOPE_API_KEY");
        assertThat(smokeTests).contains("resilience4j.circuitbreaker.instances.deepseek-primary-model");
    }

    @Test
    void failoverSourcesStayWithinProjectDefensiveRules() throws IOException {
        String pom = Files.readString(ORCHESTRATOR_POM_PATH);

        assertThat(pom).doesNotContain("spring-boot-starter-webflux");
        for (Path sourcePath : FAILOVER_SOURCE_PATHS) {
            String source = Files.readString(sourcePath);

            assertThat(source).doesNotContain("Flux<ServerSentEvent>");
            assertThat(source).doesNotContain(FORBIDDEN_SYSTEM_OUT);
            assertThat(source).doesNotContain(FORBIDDEN_RETURN_NULL);
            assertThat(source).doesNotContain("sk-");
            assertThat(EMPTY_CATCH_PATTERN.matcher(source).find()).isFalse();
        }
    }

    private static ResilientModelStreamClient resilientClient(
            ResilienceSettings resilienceSettings,
            ModelProviderClient providerClient
    ) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(resilienceSettings.failureRateThreshold())
                .slidingWindowSize(resilienceSettings.slidingWindowSize())
                .minimumNumberOfCalls(resilienceSettings.minimumNumberOfCalls())
                .waitDurationInOpenState(resilienceSettings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(resilienceSettings.permittedCallsInHalfOpenState())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(resilienceSettings.primaryTimeout())
                .cancelRunningFuture(true)
                .build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(circuitBreakerConfig)
                .circuitBreaker(PRIMARY_CIRCUIT_NAME);

        return new ResilientModelStreamClient(
                providerClient,
                circuitBreaker,
                TimeLimiterRegistry.of(timeLimiterConfig).timeLimiter(PRIMARY_CIRCUIT_NAME)
        );
    }

    private static StreamingModelService service(ModelStreamClient modelStreamClient) {
        OrchestratorStreamingModelProperties properties = new OrchestratorStreamingModelProperties();
        return new StreamingModelService(
                properties,
                modelStreamClient,
                new ModelContextAdapter(properties),
                new ModelOutputParser(properties),
                new StreamingResumePromptBuilder(),
                new StreamingSessionStore(properties)
        );
    }

    private static ResilienceSettings fastResilience() {
        return new ResilienceSettings(SHORT_TIMEOUT, 50.0F, 4, 2, SHORT_OPEN_WAIT, 1);
    }

    private static ResilienceSettings circuitResilience() {
        return new ResilienceSettings(Duration.ofMillis(250), 50.0F, TWO_CALLS, TWO_CALLS, SHORT_OPEN_WAIT, 1);
    }

    private static StreamingRequestContext initialContext(String sessionId) {
        return StreamingRequestContext.initial(REQUEST_ID, TENANT_ID, USER_ID, IDEMPOTENCY_KEY, sessionId);
    }

    private static List<String> eventNames(List<ModelStreamEvent> events) {
        return events.stream()
                .map(ModelStreamEvent::getEventName)
                .toList();
    }

    private static String tokenContent(List<ModelStreamEvent> events) {
        StringBuilder content = new StringBuilder();
        for (ModelStreamEvent event : events) {
            if (ModelStreamEvent.EVENT_TOKEN.equals(event.getEventName())) {
                content.append(event.getContent());
            }
        }
        return content.toString();
    }

    private static void assertBusinessError(Runnable invocation, ErrorCode errorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
        };
    }

    private record ResilienceSettings(
            Duration primaryTimeout,
            float failureRateThreshold,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState
    ) {
    }

    private static final class RecordingProviderClient implements ModelProviderClient {

        private final List<String> fallbackPrompts = new ArrayList<>();
        private String primaryContent;
        private String fallbackContent;
        private String partialContent;
        private Duration primaryDelay = Duration.ZERO;
        private boolean primaryFails;
        private int primaryCalls;
        private int fallbackCalls;

        private static RecordingProviderClient primarySuccess(String primaryContent) {
            RecordingProviderClient client = new RecordingProviderClient();
            client.primaryContent = primaryContent;
            client.fallbackContent = FALLBACK_CONTENT;
            return client;
        }

        private static RecordingProviderClient primaryDelayThenFallback(Duration delay, String fallbackContent) {
            RecordingProviderClient client = primaryFailureThenFallback(fallbackContent);
            client.primaryDelay = delay;
            client.primaryFails = false;
            return client;
        }

        private static RecordingProviderClient primaryFailureThenFallback(String fallbackContent) {
            RecordingProviderClient client = new RecordingProviderClient();
            client.primaryFails = true;
            client.fallbackContent = fallbackContent;
            return client;
        }

        private static RecordingProviderClient partialThenFailure(String partialContent, String fallbackContent) {
            RecordingProviderClient client = primaryFailureThenFallback(fallbackContent);
            client.partialContent = partialContent;
            return client;
        }

        @Override
        public void stream(
                ModelType modelType,
                String prompt,
                StreamingModelRequest request,
                StreamingRequestContext context,
                ModelStreamClient.TokenConsumer tokenConsumer
        ) {
            if (ModelType.DEEPSEEK == modelType) {
                streamPrimary(tokenConsumer);
                return;
            }
            streamFallback(prompt, tokenConsumer);
        }

        private void streamPrimary(ModelStreamClient.TokenConsumer tokenConsumer) {
            primaryCalls++;
            sleepIfNeeded();
            if (partialContent != null) {
                tokenConsumer.accept(partialContent);
            }
            if (primaryFails) {
                throw new ModelProviderException("provider failure");
            }
            tokenConsumer.accept(primaryContent);
        }

        private void streamFallback(String prompt, ModelStreamClient.TokenConsumer tokenConsumer) {
            fallbackCalls++;
            fallbackPrompts.add(prompt);
            tokenConsumer.accept(fallbackContent);
        }

        private void sleepIfNeeded() {
            if (primaryDelay.isZero()) {
                return;
            }
            try {
                Thread.sleep(primaryDelay.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ModelProviderException("provider interrupted");
            }
        }

        private void setPrimarySuccess(String primaryContent) {
            this.primaryFails = false;
            this.partialContent = null;
            this.primaryContent = primaryContent;
        }

        private int getPrimaryCalls() {
            return primaryCalls;
        }

        private int getFallbackCalls() {
            return fallbackCalls;
        }

        private List<String> getFallbackPrompts() {
            return fallbackPrompts;
        }
    }
}
