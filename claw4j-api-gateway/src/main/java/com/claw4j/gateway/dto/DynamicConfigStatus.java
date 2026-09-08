package com.claw4j.gateway.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Gateway-local status DTO describing the currently effective demo configuration.
 */
public final class DynamicConfigStatus {

    private static final int MINIMUM_VALID_THRESHOLD = 1;
    private static final String EFFECTIVE_THRESHOLD_FIELD = "effectiveThreshold";
    private static final String DEMO_MESSAGE_FIELD = "demoMessage";
    private static final String FALLBACK_APPLIED_FIELD = "fallbackApplied";
    private static final String INVALID_KEY_FIELD = "invalidKey";
    private static final String INVALID_THRESHOLD_MESSAGE = "effectiveThreshold must be positive";

    private final int effectiveThreshold;
    private final String demoMessage;
    private final boolean fallbackApplied;
    private final String invalidKey;

    /**
     * Creates a validated dynamic configuration status DTO.
     *
     * @param effectiveThreshold effective positive threshold value
     * @param demoMessage demo message resolved from dynamic configuration
     * @param fallbackApplied whether fallback was applied
     * @param invalidKey invalid config key when fallback was applied
     */
    @JsonCreator
    public DynamicConfigStatus(
            @JsonProperty(EFFECTIVE_THRESHOLD_FIELD) int effectiveThreshold,
            @JsonProperty(DEMO_MESSAGE_FIELD) String demoMessage,
            @JsonProperty(FALLBACK_APPLIED_FIELD) boolean fallbackApplied,
            @JsonProperty(INVALID_KEY_FIELD) String invalidKey
    ) {
        this.effectiveThreshold = requirePositiveThreshold(effectiveThreshold);
        this.demoMessage = optionalText(demoMessage);
        this.fallbackApplied = fallbackApplied;
        this.invalidKey = optionalText(invalidKey);
    }

    /**
     * Creates a validated dynamic configuration status DTO.
     *
     * @param effectiveThreshold effective positive threshold value
     * @param demoMessage demo message resolved from dynamic configuration
     * @param fallbackApplied whether fallback was applied
     * @param invalidKey invalid config key when fallback was applied
     * @return validated dynamic configuration status
     */
    public static DynamicConfigStatus of(
            int effectiveThreshold,
            String demoMessage,
            boolean fallbackApplied,
            String invalidKey
    ) {
        return new DynamicConfigStatus(
                effectiveThreshold,
                demoMessage,
                fallbackApplied,
                invalidKey
        );
    }

    /**
     * Returns the effective positive threshold value.
     *
     * @return effective threshold
     */
    public int getEffectiveThreshold() {
        return effectiveThreshold;
    }

    /**
     * Returns the demo message resolved from dynamic configuration.
     *
     * @return demo message
     */
    public String getDemoMessage() {
        return demoMessage;
    }

    /**
     * Returns whether a fallback value is in use.
     *
     * @return true when fallback is applied
     */
    public boolean isFallbackApplied() {
        return fallbackApplied;
    }

    /**
     * Returns the invalid configuration key when fallback is applied.
     *
     * @return invalid key or an empty string
     */
    public String getInvalidKey() {
        return invalidKey;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value;
    }

    private static int requirePositiveThreshold(int value) {
        if (value < MINIMUM_VALID_THRESHOLD) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, INVALID_THRESHOLD_MESSAGE);
        }
        return value;
    }
}
