package com.claw4j.orchestrator.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Describes how a model prompt was adapted to a target context window.
 */
public final class ContextAdaptationStatus {

    public static final String OUTCOME_ACCEPTED = "accepted";
    public static final String OUTCOME_SUMMARIZED = "summarized";
    public static final String OUTCOME_TRUNCATED = "truncated";
    public static final String OUTCOME_REJECTED = "rejected";

    private static final String MODEL_TYPE_FIELD = "modelType";
    private static final String STRATEGY_FIELD = "strategy";
    private static final String ORIGINAL_ESTIMATED_TOKENS_FIELD = "originalEstimatedTokens";
    private static final String ADAPTED_ESTIMATED_TOKENS_FIELD = "adaptedEstimatedTokens";
    private static final String ADAPTED_FIELD = "adapted";
    private static final String REJECTED_FIELD = "rejected";
    private static final String OUTCOME_FIELD = "outcome";
    private static final String CONTENT_FIELD = "content";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final ModelType modelType;
    private final String strategy;
    private final int originalEstimatedTokens;
    private final int adaptedEstimatedTokens;
    private final boolean adapted;
    private final boolean rejected;
    private final String outcome;
    private final String content;

    /**
     * Creates a context adaptation status.
     *
     * @param modelType target model family
     * @param strategy adaptation strategy
     * @param originalEstimatedTokens original estimated token count
     * @param adaptedEstimatedTokens adapted estimated token count
     * @param adapted whether content was adapted
     * @param rejected whether content was rejected
     * @param outcome adaptation outcome
     * @param content adapted content for model invocation
     */
    @JsonCreator
    public ContextAdaptationStatus(
            @JsonProperty(MODEL_TYPE_FIELD) ModelType modelType,
            @JsonProperty(STRATEGY_FIELD) String strategy,
            @JsonProperty(ORIGINAL_ESTIMATED_TOKENS_FIELD) int originalEstimatedTokens,
            @JsonProperty(ADAPTED_ESTIMATED_TOKENS_FIELD) int adaptedEstimatedTokens,
            @JsonProperty(ADAPTED_FIELD) boolean adapted,
            @JsonProperty(REJECTED_FIELD) boolean rejected,
            @JsonProperty(OUTCOME_FIELD) String outcome,
            @JsonProperty(CONTENT_FIELD) String content
    ) {
        this.modelType = Objects.requireNonNull(modelType, MODEL_TYPE_FIELD + REQUIRED_FIELD_SUFFIX);
        this.strategy = requireText(strategy, STRATEGY_FIELD);
        this.originalEstimatedTokens = requireNonNegative(originalEstimatedTokens, ORIGINAL_ESTIMATED_TOKENS_FIELD);
        this.adaptedEstimatedTokens = requireNonNegative(adaptedEstimatedTokens, ADAPTED_ESTIMATED_TOKENS_FIELD);
        this.adapted = adapted;
        this.rejected = rejected;
        this.outcome = requireText(outcome, OUTCOME_FIELD);
        this.content = defaultIfMissing(content);
    }

    /**
     * Creates a status for content that fits without adaptation.
     *
     * @param modelType target model family
     * @param strategy configured strategy
     * @param estimatedTokens estimated token count
     * @param content content for model invocation
     * @return accepted adaptation status
     */
    public static ContextAdaptationStatus accepted(
            ModelType modelType,
            String strategy,
            int estimatedTokens,
            String content
    ) {
        return new ContextAdaptationStatus(
                modelType,
                strategy,
                estimatedTokens,
                estimatedTokens,
                false,
                false,
                OUTCOME_ACCEPTED,
                content
        );
    }

    /**
     * Creates a status for adapted content.
     *
     * @param modelType target model family
     * @param strategy configured strategy
     * @param originalEstimatedTokens original estimated token count
     * @param adaptedEstimatedTokens adapted estimated token count
     * @param outcome adaptation outcome
     * @param content content for model invocation
     * @return adapted context status
     */
    public static ContextAdaptationStatus adapted(
            ModelType modelType,
            String strategy,
            int originalEstimatedTokens,
            int adaptedEstimatedTokens,
            String outcome,
            String content
    ) {
        return new ContextAdaptationStatus(
                modelType,
                strategy,
                originalEstimatedTokens,
                adaptedEstimatedTokens,
                true,
                false,
                outcome,
                content
        );
    }

    /**
     * Creates a status for rejected content.
     *
     * @param modelType target model family
     * @param strategy configured strategy
     * @param originalEstimatedTokens original estimated token count
     * @return rejected context status
     */
    public static ContextAdaptationStatus rejected(
            ModelType modelType,
            String strategy,
            int originalEstimatedTokens
    ) {
        return new ContextAdaptationStatus(
                modelType,
                strategy,
                originalEstimatedTokens,
                originalEstimatedTokens,
                false,
                true,
                OUTCOME_REJECTED,
                ""
        );
    }

    /**
     * Returns the target model family.
     *
     * @return model type
     */
    public ModelType getModelType() {
        return modelType;
    }

    /**
     * Returns the configured adaptation strategy.
     *
     * @return adaptation strategy
     */
    public String getStrategy() {
        return strategy;
    }

    /**
     * Returns the original estimated token count.
     *
     * @return original estimated token count
     */
    public int getOriginalEstimatedTokens() {
        return originalEstimatedTokens;
    }

    /**
     * Returns the adapted estimated token count.
     *
     * @return adapted estimated token count
     */
    public int getAdaptedEstimatedTokens() {
        return adaptedEstimatedTokens;
    }

    /**
     * Returns whether context adaptation changed the content.
     *
     * @return true when adapted
     */
    public boolean isAdapted() {
        return adapted;
    }

    /**
     * Returns whether the content was rejected.
     *
     * @return true when rejected
     */
    public boolean isRejected() {
        return rejected;
    }

    /**
     * Returns the adaptation outcome.
     *
     * @return adaptation outcome
     */
    public String getOutcome() {
        return outcome;
    }

    /**
     * Returns the adapted content for model invocation.
     *
     * @return adapted content
     */
    public String getContent() {
        return content;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + " must not be negative");
        }
        return value;
    }

    private static String defaultIfMissing(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}
