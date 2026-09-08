package com.claw4j.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds Orchestrator Sentinel proof-path rule defaults and resource names.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.sentinel.orchestrator")
public class OrchestratorSentinelProperties {

    private static final String DEFAULT_AGENT_CALL_RESOURCE_NAME = "claw4j-orchestrator-agent-call";
    private static final double DEFAULT_ERROR_RATIO_THRESHOLD = 0.5D;
    private static final int DEFAULT_MINIMUM_REQUEST_AMOUNT = 2;
    private static final int DEFAULT_STAT_INTERVAL_MS = 1000;
    private static final int DEFAULT_RECOVERY_WINDOW_SECONDS = 2;

    private String agentCallResourceName = DEFAULT_AGENT_CALL_RESOURCE_NAME;
    private double errorRatioThreshold = DEFAULT_ERROR_RATIO_THRESHOLD;
    private int minimumRequestAmount = DEFAULT_MINIMUM_REQUEST_AMOUNT;
    private int statIntervalMs = DEFAULT_STAT_INTERVAL_MS;
    private int recoveryWindowSeconds = DEFAULT_RECOVERY_WINDOW_SECONDS;

    /**
     * Returns the protected Agent-call resource name.
     *
     * @return Sentinel resource name
     */
    public String getAgentCallResourceName() {
        return agentCallResourceName;
    }

    /**
     * Updates the protected Agent-call resource name.
     *
     * @param agentCallResourceName Sentinel resource name
     */
    public void setAgentCallResourceName(String agentCallResourceName) {
        this.agentCallResourceName = defaultIfBlank(agentCallResourceName, DEFAULT_AGENT_CALL_RESOURCE_NAME);
    }

    /**
     * Returns the error-ratio threshold used by Sentinel degrade rules.
     *
     * @return error-ratio threshold
     */
    public double getErrorRatioThreshold() {
        return errorRatioThreshold;
    }

    /**
     * Updates the error-ratio threshold used by Sentinel degrade rules.
     *
     * @param errorRatioThreshold error-ratio threshold
     */
    public void setErrorRatioThreshold(double errorRatioThreshold) {
        this.errorRatioThreshold = ratioOrDefault(errorRatioThreshold);
    }

    /**
     * Returns the minimum request count before the circuit can open.
     *
     * @return minimum request amount
     */
    public int getMinimumRequestAmount() {
        return minimumRequestAmount;
    }

    /**
     * Updates the minimum request count before the circuit can open.
     *
     * @param minimumRequestAmount minimum request amount
     */
    public void setMinimumRequestAmount(int minimumRequestAmount) {
        this.minimumRequestAmount = positiveOrDefault(minimumRequestAmount, DEFAULT_MINIMUM_REQUEST_AMOUNT);
    }

    /**
     * Returns the Sentinel statistics interval in milliseconds.
     *
     * @return statistics interval in milliseconds
     */
    public int getStatIntervalMs() {
        return statIntervalMs;
    }

    /**
     * Updates the Sentinel statistics interval in milliseconds.
     *
     * @param statIntervalMs statistics interval in milliseconds
     */
    public void setStatIntervalMs(int statIntervalMs) {
        this.statIntervalMs = positiveOrDefault(statIntervalMs, DEFAULT_STAT_INTERVAL_MS);
    }

    /**
     * Returns the circuit recovery window in seconds.
     *
     * @return recovery window in seconds
     */
    public int getRecoveryWindowSeconds() {
        return recoveryWindowSeconds;
    }

    /**
     * Updates the circuit recovery window in seconds.
     *
     * @param recoveryWindowSeconds recovery window in seconds
     */
    public void setRecoveryWindowSeconds(int recoveryWindowSeconds) {
        this.recoveryWindowSeconds = positiveOrDefault(recoveryWindowSeconds, DEFAULT_RECOVERY_WINDOW_SECONDS);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static double ratioOrDefault(double value) {
        if (value <= 0.0D || value > 1.0D) {
            return DEFAULT_ERROR_RATIO_THRESHOLD;
        }
        return value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return value;
    }
}
