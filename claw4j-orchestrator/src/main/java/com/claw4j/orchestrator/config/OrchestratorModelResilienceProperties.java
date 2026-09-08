package com.claw4j.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds Resilience4j timeout and circuit-breaker settings for primary model calls.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.model.resilience")
public class OrchestratorModelResilienceProperties {

    private static final Duration DEFAULT_PRIMARY_TIMEOUT = Duration.ofSeconds(30);
    private static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50.0F;
    private static final int DEFAULT_SLIDING_WINDOW_SIZE = 10;
    private static final int DEFAULT_MINIMUM_NUMBER_OF_CALLS = 4;
    private static final Duration DEFAULT_WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
    private static final int DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 1;
    private static final float MIN_FAILURE_RATE_THRESHOLD = 1.0F;
    private static final float MAX_FAILURE_RATE_THRESHOLD = 100.0F;

    private Duration primaryTimeout = DEFAULT_PRIMARY_TIMEOUT;
    private float failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
    private int slidingWindowSize = DEFAULT_SLIDING_WINDOW_SIZE;
    private int minimumNumberOfCalls = DEFAULT_MINIMUM_NUMBER_OF_CALLS;
    private Duration waitDurationInOpenState = DEFAULT_WAIT_DURATION_IN_OPEN_STATE;
    private int permittedCallsInHalfOpenState = DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE;

    /**
     * Returns the primary model timeout.
     *
     * @return primary model timeout
     */
    public Duration getPrimaryTimeout() {
        return primaryTimeout;
    }

    /**
     * Updates the primary model timeout.
     *
     * @param primaryTimeout primary model timeout
     */
    public void setPrimaryTimeout(Duration primaryTimeout) {
        this.primaryTimeout = positiveDurationOrDefault(primaryTimeout, DEFAULT_PRIMARY_TIMEOUT);
    }

    /**
     * Returns the circuit-breaker failure-rate threshold.
     *
     * @return failure-rate threshold percentage
     */
    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    /**
     * Updates the circuit-breaker failure-rate threshold.
     *
     * @param failureRateThreshold failure-rate threshold percentage
     */
    public void setFailureRateThreshold(float failureRateThreshold) {
        if (Float.isFinite(failureRateThreshold)
                && failureRateThreshold >= MIN_FAILURE_RATE_THRESHOLD
                && failureRateThreshold <= MAX_FAILURE_RATE_THRESHOLD) {
            this.failureRateThreshold = failureRateThreshold;
            return;
        }
        this.failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
    }

    /**
     * Returns the circuit sliding-window size.
     *
     * @return sliding-window size
     */
    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    /**
     * Updates the circuit sliding-window size.
     *
     * @param slidingWindowSize sliding-window size
     */
    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = positiveIntOrDefault(slidingWindowSize, DEFAULT_SLIDING_WINDOW_SIZE);
    }

    /**
     * Returns the minimum call count before calculating failure rate.
     *
     * @return minimum call count
     */
    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    /**
     * Updates the minimum call count before calculating failure rate.
     *
     * @param minimumNumberOfCalls minimum call count
     */
    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = positiveIntOrDefault(
                minimumNumberOfCalls,
                DEFAULT_MINIMUM_NUMBER_OF_CALLS
        );
    }

    /**
     * Returns the open-state wait duration.
     *
     * @return open-state wait duration
     */
    public Duration getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    /**
     * Updates the open-state wait duration.
     *
     * @param waitDurationInOpenState open-state wait duration
     */
    public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
        this.waitDurationInOpenState = positiveDurationOrDefault(
                waitDurationInOpenState,
                DEFAULT_WAIT_DURATION_IN_OPEN_STATE
        );
    }

    /**
     * Returns the permitted half-open probe call count.
     *
     * @return permitted half-open probe call count
     */
    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    /**
     * Updates the permitted half-open probe call count.
     *
     * @param permittedCallsInHalfOpenState permitted half-open probe call count
     */
    public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
        this.permittedCallsInHalfOpenState = positiveIntOrDefault(
                permittedCallsInHalfOpenState,
                DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE
        );
    }

    private static Duration positiveDurationOrDefault(Duration value, Duration defaultValue) {
        if (value == null || value.isZero() || value.isNegative()) {
            return defaultValue;
        }
        return value;
    }

    private static int positiveIntOrDefault(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return value;
    }
}
