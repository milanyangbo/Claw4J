package com.claw4j.orchestrator.service;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties.TruncationStrategy;
import com.claw4j.orchestrator.dto.ContextAdaptationStatus;
import com.claw4j.orchestrator.dto.ModelType;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Adapts prompt context to the configured model context window.
 */
@Service
public class ModelContextAdapter {

    private static final int CHARACTERS_PER_ESTIMATED_TOKEN = 4;
    private static final int HEAD_TAIL_SPLIT_DIVISOR = 2;
    private static final String SUMMARY_MARKER = "\n[context summarized to fit target model budget]\n";
    private static final String TRUNCATION_MARKER = "\n[context truncated to fit target model budget]\n";

    private final OrchestratorStreamingModelProperties properties;

    /**
     * Creates the model context adapter.
     *
     * @param properties streaming model properties
     */
    public ModelContextAdapter(OrchestratorStreamingModelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Adapts content for the selected model context budget.
     *
     * @param content original content
     * @param modelType target model family
     * @return context adaptation status
     */
    public ContextAdaptationStatus adapt(String content, ModelType modelType) {
        Objects.requireNonNull(modelType, "modelType must not be null");
        String source = requireText(content, "content");
        TruncationStrategy strategy = properties.getContext().getTruncationStrategy();
        int originalTokens = estimateTokens(source);
        int maxTokens = maxTokensFor(modelType);
        String strategyName = strategy.name().toLowerCase(Locale.ROOT);
        if (originalTokens <= maxTokens) {
            return ContextAdaptationStatus.accepted(modelType, strategyName, originalTokens, source);
        }
        if (TruncationStrategy.REJECT == strategy) {
            return ContextAdaptationStatus.rejected(modelType, strategyName, originalTokens);
        }
        String adaptedContent = adaptContent(source, maxTokens, strategy);
        return ContextAdaptationStatus.adapted(
                modelType,
                strategyName,
                originalTokens,
                estimateTokens(adaptedContent),
                outcomeFor(strategy),
                adaptedContent
        );
    }

    /**
     * Estimates token count deterministically for local proof tests.
     *
     * @param content content to estimate
     * @return estimated token count
     */
    public int estimateTokens(String content) {
        String source = requireText(content, "content");
        return Math.max(1, (source.length() + CHARACTERS_PER_ESTIMATED_TOKEN - 1) / CHARACTERS_PER_ESTIMATED_TOKEN);
    }

    private int maxTokensFor(ModelType modelType) {
        if (properties.getProofClient().getFallbackModelType() == modelType) {
            return properties.getContext().getFallbackMaxTokens();
        }
        return properties.getContext().getPrimaryMaxTokens();
    }

    private static String adaptContent(String source, int maxTokens, TruncationStrategy strategy) {
        int maxCharacters = maxTokens * CHARACTERS_PER_ESTIMATED_TOKEN;
        String marker = TruncationStrategy.SUMMARY == strategy ? SUMMARY_MARKER : TRUNCATION_MARKER;
        if (source.length() <= maxCharacters) {
            return source;
        }
        int availableCharacters = maxCharacters - marker.length();
        if (availableCharacters <= HEAD_TAIL_SPLIT_DIVISOR) {
            return source.substring(0, Math.min(source.length(), maxCharacters));
        }
        int headLength = availableCharacters / HEAD_TAIL_SPLIT_DIVISOR;
        int tailLength = availableCharacters - headLength;
        String head = source.substring(0, headLength);
        String tail = source.substring(source.length() - tailLength);
        return head + marker + tail;
    }

    private static String outcomeFor(TruncationStrategy strategy) {
        if (TruncationStrategy.SUMMARY == strategy) {
            return ContextAdaptationStatus.OUTCOME_SUMMARIZED;
        }
        return ContextAdaptationStatus.OUTCOME_TRUNCATED;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + " is required");
        }
        return value;
    }
}
