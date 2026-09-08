package com.claw4j.orchestrator;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.client.ModelProviderClient;
import com.claw4j.orchestrator.client.SpringAiModelProviderClient;
import com.claw4j.orchestrator.config.OrchestratorModelClientProperties;
import com.claw4j.orchestrator.config.OrchestratorModelClientProperties.ClientMode;
import com.claw4j.orchestrator.config.OrchestratorModelResilienceProperties;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.dto.ModelStreamEvent;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import com.claw4j.orchestrator.service.DeterministicModelStreamClient;
import com.claw4j.orchestrator.service.ModelContextAdapter;
import com.claw4j.orchestrator.service.ModelOutputParser;
import com.claw4j.orchestrator.service.ModelStreamClient;
import com.claw4j.orchestrator.service.ResilientModelStreamClient;
import com.claw4j.orchestrator.service.StreamingModelService;
import com.claw4j.orchestrator.service.StreamingResumePromptBuilder;
import com.claw4j.orchestrator.service.StreamingSessionStore;
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
import org.springframework.boot.context.properties.ConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Orchestrator model-provider failover and Resilience4j contracts.
 */
class ModelFailoverResilienceContractTest {

    private static final Path ORCHESTRATOR_POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path README_PATH = Path.of("../README.md");
    private static final Path SMOKE_TEST_PATH = Path.of("../MODEL_FAILOVER_SMOKE_TESTS.md");
    private static final Path MODEL_CLIENT_PROPERTIES_PATH = Path.of(
            "src/main/java/com/claw4j/orchestrator/config/OrchestratorModelClientProperties.java"
    );
    private static final Path SPRING_AI_PROVIDER_CLIENT_PATH = Path.of(
            "src/main/java/com/claw4j/orchestrator/client/SpringAiModelProviderClient.java"
    );
    private static final List<Path> FAILOVER_SOURCE_PATHS = List.of(
            Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorModelClientProperties.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/config/OrchestratorModelResilienceProperties.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/client/ModelProviderClient.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/client/SpringAiModelProviderClient.java"),
            Path.of("src/main/java/com/claw4j/orchestrator/service/ResilientModelStreamClient.java")
    );
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");
    private static final String REQUEST_ID = "req-failover";
    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "user-a";
    private static final String IDEMPOTENCY_KEY = "idem-failover";
    private static final String SESSION_ID = "session-failover";
    private static final String PRIMARY_CONTENT = "deepseek answer";
    private static final String FALLBACK_CONTENT = "qwen answer";
    private static final String PARTIAL_PRIMARY_CONTENT = "deepseek partial ";
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(25);
    private static final Duration SHORT_OPEN_WAIT = Duration.ofMillis(80);
    private static final int TWO_CALLS = 2;

    @Test
    void moduleDeclaresModelProviderAndResilienceDependencies() throws IOException {
        String pom = Files.readString(ORCHESTRATOR_POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>");
        assertThat(pom).contains("<artifactId>spring-ai-starter-model-deepseek</artifactId>");
        assertThat(pom).contains("<artifactId>spring-ai-alibaba-starter-dashscope</artifactId>");
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
    void springAiProviderClientRequiresOfficialCredentialsInRealMode() {
        assertBusinessError(
                () -> new SpringAiModelProviderClient(
                        emptyProvider(),
                        emptyProvider(),
                        new DeepSeekConnectionProperties(),
                        new DeepSeekChatProperties(),
                        new DashScopeConnectionProperties(),
                        new DashScopeChatProperties()
                ),
                ErrorCode.MODEL_PROVIDER_CONFIGURATION_INVALID
        );
    }

    @Test
    void modelClientPropertiesOnlySelectClientMode() throws IOException {
        ConfigurationProperties configurationProperties = OrchestratorModelClientProperties.class
                .getAnnotation(ConfigurationProperties.class);
        OrchestratorModelClientProperties properties = new OrchestratorModelClientProperties();
        String source = Files.readString(MODEL_CLIENT_PROPERTIES_PATH);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("claw4j.model.client");
        assertThat(properties.getMode()).isEqualTo(ClientMode.DETERMINISTIC);
        properties.setMode(ClientMode.REAL);
        assertThat(properties.isRealMode()).isTrue();
        properties.setMode(null);
        assertThat(properties.getMode()).isEqualTo(ClientMode.REAL);
        assertThat(source).doesNotContain("apiKey");
        assertThat(source).doesNotContain("modelName");
        assertThat(source).doesNotContain("getDeepseek");
        assertThat(source).doesNotContain("getQwen");
    }

    @Test
    void modelResiliencePropertiesNormalizeInvalidValues() {
        ConfigurationProperties configurationProperties = OrchestratorModelResilienceProperties.class
                .getAnnotation(ConfigurationProperties.class);
        OrchestratorModelResilienceProperties properties = new OrchestratorModelResilienceProperties();

        properties.setPrimaryTimeout(Duration.ZERO);
        properties.setFailureRateThreshold(0.0F);
        properties.setSlidingWindowSize(0);
        properties.setMinimumNumberOfCalls(0);
        properties.setWaitDurationInOpenState(Duration.ZERO);
        properties.setPermittedCallsInHalfOpenState(0);

        assertThat(configurationProperties).isNotNull();
        assertThat(configurationProperties.prefix()).isEqualTo("claw4j.model.resilience");
        assertThat(properties.getPrimaryTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getFailureRateThreshold()).isEqualTo(50.0F);
        assertThat(properties.getSlidingWindowSize()).isEqualTo(10);
        assertThat(properties.getMinimumNumberOfCalls()).isEqualTo(4);
        assertThat(properties.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getPermittedCallsInHalfOpenState()).isEqualTo(1);
    }

    @Test
    void applicationConfigurationExposesModelFailoverSettingsWithoutSecretLiterals() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("client:");
        assertThat(applicationYaml).contains("mode: ${CLAW4J_MODEL_CLIENT_MODE:deterministic}");
        assertThat(applicationYaml).contains("deepseek:");
        assertThat(applicationYaml).contains("api-key: ${CLAW4J_DEEPSEEK_API_KEY:}");
        assertThat(applicationYaml).contains("model: ${CLAW4J_DEEPSEEK_MODEL_NAME:deepseek-reasoner}");
        assertThat(applicationYaml).contains("dashscope:");
        assertThat(applicationYaml).contains("api-key: ${CLAW4J_DASHSCOPE_API_KEY:}");
        assertThat(applicationYaml).contains("model: ${CLAW4J_QWEN_MODEL_NAME:qwen-plus}");
        assertThat(applicationYaml).contains("incremental-output: true");
        assertThat(applicationYaml).contains("fallback-model-type: ${CLAW4J_MODEL_STREAMING_FALLBACK_MODEL_TYPE:qwen}");
        assertThat(applicationYaml).contains("primary-timeout: ${CLAW4J_MODEL_PRIMARY_TIMEOUT:30s}");
        assertThat(applicationYaml).doesNotContain("model-name: ${CLAW4J_DEEPSEEK_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("model-name: ${CLAW4J_QWEN_MODEL_NAME");
        assertThat(applicationYaml).doesNotContain("sk-");
    }

    @Test
    void primarySuccessDoesNotInvokeFallback() {
        RecordingProviderClient providerClient = RecordingProviderClient.primarySuccess(PRIMARY_CONTENT);
        StreamingModelService service = service(resilientClient(realProperties(), fastResilience(), providerClient));

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
        StreamingModelService service = service(resilientClient(realProperties(), fastResilience(), providerClient));

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
        StreamingModelService service = service(resilientClient(realProperties(), fastResilience(), providerClient));

        List<ModelStreamEvent> events = service.streamEvents(
                StreamingModelRequest.of("partial"),
                initialContext("partial")
        );

        assertThat(tokenContent(events)).isEqualTo(PARTIAL_PRIMARY_CONTENT + FALLBACK_CONTENT);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getEventName()).isEqualTo(ModelStreamEvent.EVENT_FALLBACK_START);
            assertThat(event.getStatusCode()).isEqualTo(ModelStreamClient.STATUS_PRIMARY_INTERRUPTED);
        });
    }

    @Test
    void errorRatioOpensPrimaryCircuitAndShortCircuitsToFallback() {
        RecordingProviderClient providerClient = RecordingProviderClient.primaryFailureThenFallback(FALLBACK_CONTENT);
        StreamingModelService service = service(resilientClient(realProperties(), circuitResilience(), providerClient));

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
        StreamingModelService service = service(resilientClient(realProperties(), circuitResilience(), providerClient));

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
        StreamingModelService service = service(resilientClient(realProperties(), circuitResilience(), providerClient));

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
        StreamingModelService service = service(resilientClient(realProperties(), fastResilience(), providerClient));

        assertBusinessError(
                () -> service.streamEvents(StreamingModelRequest.of("invalid"), initialContext(" ")),
                ErrorCode.INVALID_REQUEST
        );

        assertThat(providerClient.getPrimaryCalls()).isZero();
        assertThat(providerClient.getFallbackCalls()).isZero();
    }

    @Test
    void deterministicModeDoesNotRequireProviderCredentials() {
        OrchestratorModelClientProperties properties = new OrchestratorModelClientProperties();
        StreamingModelService service = service(new ResilientModelStreamClient(
                properties,
                fastResilience(),
                new DeterministicModelStreamClient(streamingProperties()),
                RecordingProviderClient.primarySuccess(PRIMARY_CONTENT)
        ));

        List<ModelStreamEvent> events = service.streamEvents(
                StreamingModelRequest.of("deterministic"),
                initialContext("deterministic")
        );

        assertThat(tokenContent(events)).isEqualTo("primary model response");
    }

    @Test
    void documentationLinksModelFailoverSmokeTests() throws IOException {
        String readme = Files.readString(README_PATH);
        String smokeTests = Files.readString(SMOKE_TEST_PATH);

        assertThat(readme).contains("MODEL_FAILOVER_SMOKE_TESTS.md");
        assertThat(smokeTests).contains("curl -N -sS");
        assertThat(smokeTests).contains("CLAW4J_MODEL_CLIENT_MODE=real");
        assertThat(smokeTests).contains("CLAW4J_DEEPSEEK_API_KEY");
        assertThat(smokeTests).contains("CLAW4J_DASHSCOPE_API_KEY");
    }

    @Test
    void failoverSourcesStayWithinProjectDefensiveRules() throws IOException {
        String pom = Files.readString(ORCHESTRATOR_POM_PATH);

        assertThat(pom).doesNotContain("spring-boot-starter-webflux");
        for (Path sourcePath : FAILOVER_SOURCE_PATHS) {
            String source = Files.readString(sourcePath);

            assertThat(source).doesNotContain("Flux<ServerSentEvent>");
            assertThat(source).doesNotContain("System.out.println");
            assertThat(source).doesNotContain("return null");
            assertThat(source).doesNotContain("sk-");
            assertThat(EMPTY_CATCH_PATTERN.matcher(source).find()).isFalse();
        }
    }

    private static ResilientModelStreamClient resilientClient(
            OrchestratorModelClientProperties clientProperties,
            OrchestratorModelResilienceProperties resilienceProperties,
            ModelProviderClient providerClient
    ) {
        return new ResilientModelStreamClient(
                clientProperties,
                resilienceProperties,
                new DeterministicModelStreamClient(streamingProperties()),
                providerClient
        );
    }

    private static StreamingModelService service(ModelStreamClient modelStreamClient) {
        OrchestratorStreamingModelProperties properties = streamingProperties();
        return new StreamingModelService(
                properties,
                modelStreamClient,
                new ModelContextAdapter(properties),
                new ModelOutputParser(properties),
                new StreamingResumePromptBuilder(),
                new StreamingSessionStore(properties)
        );
    }

    private static OrchestratorStreamingModelProperties streamingProperties() {
        return new OrchestratorStreamingModelProperties();
    }

    private static OrchestratorModelClientProperties realProperties() {
        OrchestratorModelClientProperties properties = new OrchestratorModelClientProperties();
        properties.setMode(ClientMode.REAL);
        return properties;
    }

    private static OrchestratorModelResilienceProperties fastResilience() {
        OrchestratorModelResilienceProperties properties = new OrchestratorModelResilienceProperties();
        properties.setPrimaryTimeout(SHORT_TIMEOUT);
        properties.setFailureRateThreshold(50.0F);
        properties.setSlidingWindowSize(4);
        properties.setMinimumNumberOfCalls(2);
        properties.setWaitDurationInOpenState(SHORT_OPEN_WAIT);
        properties.setPermittedCallsInHalfOpenState(1);
        return properties;
    }

    private static OrchestratorModelResilienceProperties circuitResilience() {
        OrchestratorModelResilienceProperties properties = fastResilience();
        properties.setPrimaryTimeout(Duration.ofMillis(250));
        properties.setSlidingWindowSize(TWO_CALLS);
        properties.setMinimumNumberOfCalls(TWO_CALLS);
        return properties;
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
                throw new ModelProviderClient.ModelProviderException("provider 5xx");
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
                throw new ModelProviderClient.ModelProviderException("provider interrupted");
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
    }
}
