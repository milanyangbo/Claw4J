package com.claw4j.orchestrator.client;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import com.claw4j.orchestrator.service.ModelStreamClient;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapts Spring AI DeepSeek and Spring AI Alibaba DashScope streams to Claw4J callbacks.
 */
@Component
@ConditionalOnProperty(prefix = "claw4j.model.client", name = "mode", havingValue = "real")
@EnableConfigurationProperties({
        DeepSeekConnectionProperties.class,
        DeepSeekChatProperties.class,
        DashScopeConnectionProperties.class,
        DashScopeChatProperties.class
})
public class SpringAiModelProviderClient implements ModelProviderClient {

    private static final String PROVIDER_FAILURE_MESSAGE = "model provider invocation failed";
    private static final String UNSUPPORTED_MODEL_MESSAGE = "unsupported model provider type";
    private static final String PROMPT_FIELD = "prompt";
    private static final String EMPTY_TEXT = "";
    private static final String DEEPSEEK_PROVIDER_NAME = "DeepSeek";
    private static final String QWEN_PROVIDER_NAME = "Qwen";
    private static final String OFFICIAL_CREDENTIAL_MESSAGE = " API key is required in Spring AI official configuration";
    private static final int PROVIDER_RETRY_LIMIT = 0;

    private final DeepSeekConnectionProperties deepSeekConnectionProperties;
    private final DeepSeekChatProperties deepSeekChatProperties;
    private final DashScopeConnectionProperties dashScopeConnectionProperties;
    private final DashScopeChatProperties dashScopeChatProperties;
    private final DeepSeekChatModel deepSeekChatModel;
    private final DashScopeChatModel qwenChatModel;

    /**
     * Creates the Spring AI provider client.
     *
     * @param deepSeekChatModel optional auto-configured DeepSeek chat model
     * @param dashScopeChatModel optional auto-configured DashScope chat model
     * @param deepSeekConnectionProperties official DeepSeek connection properties
     * @param deepSeekChatProperties official DeepSeek chat properties
     * @param dashScopeConnectionProperties official DashScope connection properties
     * @param dashScopeChatProperties official DashScope chat properties
     */
    public SpringAiModelProviderClient(
            ObjectProvider<DeepSeekChatModel> deepSeekChatModel,
            ObjectProvider<DashScopeChatModel> dashScopeChatModel,
            DeepSeekConnectionProperties deepSeekConnectionProperties,
            DeepSeekChatProperties deepSeekChatProperties,
            DashScopeConnectionProperties dashScopeConnectionProperties,
            DashScopeChatProperties dashScopeChatProperties
    ) {
        this.deepSeekConnectionProperties = Objects.requireNonNull(
                deepSeekConnectionProperties,
                "deepSeekConnectionProperties must not be null"
        );
        this.deepSeekChatProperties = Objects.requireNonNull(
                deepSeekChatProperties,
                "deepSeekChatProperties must not be null"
        );
        this.dashScopeConnectionProperties = Objects.requireNonNull(
                dashScopeConnectionProperties,
                "dashScopeConnectionProperties must not be null"
        );
        this.dashScopeChatProperties = Objects.requireNonNull(
                dashScopeChatProperties,
                "dashScopeChatProperties must not be null"
        );
        validateOfficialCredentials();
        this.deepSeekChatModel = deepSeekChatModel.getIfAvailable(this::buildDeepSeekChatModel);
        this.qwenChatModel = dashScopeChatModel.getIfAvailable(this::buildQwenChatModel);
    }

    /**
     * Streams raw provider output through the Claw4J callback contract.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for provider invocation
     * @param request validated business streaming request
     * @param context validated Header-derived request context
     * @param tokenConsumer token callback for raw provider output
     */
    @Override
    public void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
            StreamingRequestContext context,
            ModelStreamClient.TokenConsumer tokenConsumer
    ) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        requireText(prompt, PROMPT_FIELD);
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(tokenConsumer, "tokenConsumer must not be null");

        try {
            modelFor(modelType)
                    .stream(promptFor(modelType, prompt))
                    .map(SpringAiModelProviderClient::extractVisibleContent)
                    .filter(Predicate.not(String::isBlank))
                    .doOnNext(tokenConsumer::accept)
                    .blockLast();
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException(PROVIDER_FAILURE_MESSAGE, exception);
        }
    }

    private StreamingChatModel modelFor(ModelType modelType) {
        if (ModelType.DEEPSEEK == modelType) {
            return deepSeekChatModel;
        }
        if (isQwenFamily(modelType)) {
            return qwenChatModel;
        }
        throw new ModelProviderException(UNSUPPORTED_MODEL_MESSAGE);
    }

    private Prompt promptFor(ModelType modelType, String prompt) {
        return new Prompt(prompt, optionsFor(modelType));
    }

    private ChatOptions optionsFor(ModelType modelType) {
        if (ModelType.DEEPSEEK == modelType) {
            return deepSeekChatProperties.getOptions();
        }
        if (isQwenFamily(modelType)) {
            return dashScopeChatProperties.getOptions();
        }
        throw new ModelProviderException(UNSUPPORTED_MODEL_MESSAGE);
    }

    private static boolean isQwenFamily(ModelType modelType) {
        return ModelType.QWEN == modelType || ModelType.QWQ == modelType;
    }

    private DeepSeekChatModel buildDeepSeekChatModel() {
        DeepSeekApi deepSeekApi = buildDeepSeekApi();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(deepSeekChatProperties.getOptions())
                .toolCallingManager(toolCallingManager())
                .retryTemplate(retryTemplate())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private DashScopeChatModel buildQwenChatModel() {
        DashScopeApi dashScopeApi = buildDashScopeApi();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(dashScopeChatProperties.getOptions())
                .toolCallingManager(toolCallingManager())
                .retryTemplate(retryTemplate())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private DeepSeekApi buildDeepSeekApi() {
        DeepSeekApi.Builder builder = DeepSeekApi.builder()
                .apiKey(requireOfficialCredential(DEEPSEEK_PROVIDER_NAME, deepSeekApiKey()));
        if (hasText(deepSeekBaseUrl())) {
            builder.baseUrl(deepSeekBaseUrl());
        }
        if (hasText(deepSeekChatProperties.getCompletionsPath())) {
            builder.completionsPath(deepSeekChatProperties.getCompletionsPath());
        }
        if (hasText(deepSeekChatProperties.getBetaPrefixPath())) {
            builder.betaPrefixPath(deepSeekChatProperties.getBetaPrefixPath());
        }
        return builder.build();
    }

    private DashScopeApi buildDashScopeApi() {
        DashScopeApi.Builder builder = DashScopeApi.builder()
                .apiKey(requireOfficialCredential(QWEN_PROVIDER_NAME, dashScopeApiKey()));
        if (hasText(dashScopeBaseUrl())) {
            builder.baseUrl(dashScopeBaseUrl());
        }
        if (hasText(dashScopeWorkspaceId())) {
            builder.workSpaceId(dashScopeWorkspaceId());
        }
        if (hasText(dashScopeChatProperties.getCompletionsPath())) {
            builder.completionsPath(dashScopeChatProperties.getCompletionsPath());
        }
        return builder.build();
    }

    private static ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static RetryTemplate retryTemplate() {
        return new RetryTemplate(RetryPolicy.withMaxRetries(PROVIDER_RETRY_LIMIT));
    }

    private void validateOfficialCredentials() {
        requireOfficialCredential(DEEPSEEK_PROVIDER_NAME, deepSeekApiKey());
        requireOfficialCredential(QWEN_PROVIDER_NAME, dashScopeApiKey());
    }

    private String deepSeekApiKey() {
        return firstText(deepSeekChatProperties.getApiKey(), deepSeekConnectionProperties.getApiKey());
    }

    private String deepSeekBaseUrl() {
        return firstText(deepSeekChatProperties.getBaseUrl(), deepSeekConnectionProperties.getBaseUrl());
    }

    private String dashScopeApiKey() {
        return firstText(dashScopeChatProperties.getApiKey(), dashScopeConnectionProperties.getApiKey());
    }

    private String dashScopeBaseUrl() {
        return firstText(dashScopeChatProperties.getBaseUrl(), dashScopeConnectionProperties.getBaseUrl());
    }

    private String dashScopeWorkspaceId() {
        return firstText(dashScopeChatProperties.getWorkspaceId(), dashScopeConnectionProperties.getWorkspaceId());
    }

    private static String extractVisibleContent(ChatResponse response) {
        if (response == null) {
            return EMPTY_TEXT;
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return EMPTY_TEXT;
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return EMPTY_TEXT;
        }
        String text = output.getText();
        if (text == null) {
            return EMPTY_TEXT;
        }
        return text;
    }

    private static String requireOfficialCredential(String providerName, String credential) {
        if (!hasText(credential)) {
            throw new BusinessException(
                    ErrorCode.MODEL_PROVIDER_CONFIGURATION_INVALID,
                    providerName + OFFICIAL_CREDENTIAL_MESSAGE
            );
        }
        return credential.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ModelProviderException(fieldName + " is required");
        }
        return value;
    }

    private static String firstText(String primary, String fallback) {
        if (hasText(primary)) {
            return primary.trim();
        }
        if (hasText(fallback)) {
            return fallback.trim();
        }
        return EMPTY_TEXT;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
