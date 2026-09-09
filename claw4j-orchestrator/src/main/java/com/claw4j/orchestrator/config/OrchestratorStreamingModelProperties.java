package com.claw4j.orchestrator.config;

import com.claw4j.orchestrator.dto.ModelType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds Orchestrator streaming model governance configuration.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.model.streaming")
public class OrchestratorStreamingModelProperties {

    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_TTFB_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SESSION_RETENTION = Duration.ofMinutes(10);
    private static final int DEFAULT_RESUME_BUFFER_SIZE = 10_000;
    private static final int DEFAULT_PRIMARY_MAX_TOKENS = 128_000;
    private static final int DEFAULT_FALLBACK_MAX_TOKENS = 32_000;

    private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
    private final Fallback fallback = new Fallback();
    private final Context context = new Context();
    private final Parser parser = new Parser();
    private final Routing routing = new Routing();

    /**
     * Returns the MVC SSE response timeout.
     *
     * @return response timeout
     */
    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /**
     * Updates the MVC SSE response timeout.
     *
     * @param responseTimeout response timeout
     */
    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = positiveDurationOrDefault(responseTimeout, DEFAULT_RESPONSE_TIMEOUT);
    }

    /**
     * Returns fallback and resume configuration.
     *
     * @return fallback configuration
     */
    public Fallback getFallback() {
        return fallback;
    }

    /**
     * Returns context-window adaptation configuration.
     *
     * @return context configuration
     */
    public Context getContext() {
        return context;
    }

    /**
     * Returns output parser configuration.
     *
     * @return parser configuration
     */
    public Parser getParser() {
        return parser;
    }

    /**
     * Returns primary and fallback model routing configuration.
     *
     * @return model routing configuration
     */
    public Routing getRouting() {
        return routing;
    }

    /**
     * Context adaptation strategies supported by the streaming path.
     */
    public enum TruncationStrategy {
        SUMMARY,
        TRUNCATE,
        REJECT
    }

    /**
     * Fallback and resume configuration.
     */
    public static final class Fallback {

        private Duration ttfbTimeout = DEFAULT_TTFB_TIMEOUT;
        private boolean resumeEnabled = true;
        private int resumeBufferSize = DEFAULT_RESUME_BUFFER_SIZE;
        private Duration sessionRetention = DEFAULT_SESSION_RETENTION;

        /**
         * Returns the primary model time-to-first-byte timeout.
         *
         * @return TTFB timeout
         */
        public Duration getTtfbTimeout() {
            return ttfbTimeout;
        }

        /**
         * Updates the primary model time-to-first-byte timeout.
         *
         * @param ttfbTimeout TTFB timeout
         */
        public void setTtfbTimeout(Duration ttfbTimeout) {
            this.ttfbTimeout = positiveDurationOrDefault(ttfbTimeout, DEFAULT_TTFB_TIMEOUT);
        }

        /**
         * Returns whether fallback resume is enabled.
         *
         * @return true when fallback resume is enabled
         */
        public boolean isResumeEnabled() {
            return resumeEnabled;
        }

        /**
         * Updates whether fallback resume is enabled.
         *
         * @param resumeEnabled true when fallback resume is enabled
         */
        public void setResumeEnabled(boolean resumeEnabled) {
            this.resumeEnabled = resumeEnabled;
        }

        /**
         * Returns the maximum retained user-visible stream content size.
         *
         * @return resume buffer size
         */
        public int getResumeBufferSize() {
            return resumeBufferSize;
        }

        /**
         * Updates the maximum retained user-visible stream content size.
         *
         * @param resumeBufferSize resume buffer size
         */
        public void setResumeBufferSize(int resumeBufferSize) {
            this.resumeBufferSize = positiveIntOrDefault(resumeBufferSize, DEFAULT_RESUME_BUFFER_SIZE);
        }

        /**
         * Returns the in-memory session retention window.
         *
         * @return session retention window
         */
        public Duration getSessionRetention() {
            return sessionRetention;
        }

        /**
         * Updates the in-memory session retention window.
         *
         * @param sessionRetention session retention window
         */
        public void setSessionRetention(Duration sessionRetention) {
            this.sessionRetention = positiveDurationOrDefault(sessionRetention, DEFAULT_SESSION_RETENTION);
        }
    }

    /**
     * Context-window adaptation configuration.
     */
    public static final class Context {

        private int primaryMaxTokens = DEFAULT_PRIMARY_MAX_TOKENS;
        private int fallbackMaxTokens = DEFAULT_FALLBACK_MAX_TOKENS;
        private TruncationStrategy truncationStrategy = TruncationStrategy.SUMMARY;

        /**
         * Returns the primary model context budget.
         *
         * @return primary max tokens
         */
        public int getPrimaryMaxTokens() {
            return primaryMaxTokens;
        }

        /**
         * Updates the primary model context budget.
         *
         * @param primaryMaxTokens primary max tokens
         */
        public void setPrimaryMaxTokens(int primaryMaxTokens) {
            this.primaryMaxTokens = positiveIntOrDefault(primaryMaxTokens, DEFAULT_PRIMARY_MAX_TOKENS);
        }

        /**
         * Returns the fallback model context budget.
         *
         * @return fallback max tokens
         */
        public int getFallbackMaxTokens() {
            return fallbackMaxTokens;
        }

        /**
         * Updates the fallback model context budget.
         *
         * @param fallbackMaxTokens fallback max tokens
         */
        public void setFallbackMaxTokens(int fallbackMaxTokens) {
            this.fallbackMaxTokens = positiveIntOrDefault(fallbackMaxTokens, DEFAULT_FALLBACK_MAX_TOKENS);
        }

        /**
         * Returns the configured context adaptation strategy.
         *
         * @return context adaptation strategy
         */
        public TruncationStrategy getTruncationStrategy() {
            return truncationStrategy;
        }

        /**
         * Updates the configured context adaptation strategy.
         *
         * @param truncationStrategy context adaptation strategy
         */
        public void setTruncationStrategy(TruncationStrategy truncationStrategy) {
            if (truncationStrategy != null) {
                this.truncationStrategy = truncationStrategy;
            }
        }
    }

    /**
     * Model output parser configuration.
     */
    public static final class Parser {

        private boolean rejectMalformedOutput = true;

        /**
         * Returns whether malformed output should be rejected.
         *
         * @return true when malformed output should be rejected
         */
        public boolean isRejectMalformedOutput() {
            return rejectMalformedOutput;
        }

        /**
         * Updates whether malformed output should be rejected.
         *
         * @param rejectMalformedOutput true when malformed output should be rejected
         */
        public void setRejectMalformedOutput(boolean rejectMalformedOutput) {
            this.rejectMalformedOutput = rejectMalformedOutput;
        }
    }

    /**
     * Primary and fallback model family routing.
     */
    public static final class Routing {

        private ModelType primaryModelType = ModelType.DEEPSEEK;
        private ModelType fallbackModelType = ModelType.QWEN;

        /**
         * Returns the primary model type.
         *
         * @return primary model type
         */
        public ModelType getPrimaryModelType() {
            return primaryModelType;
        }

        /**
         * Updates the primary model type.
         *
         * @param primaryModelType primary model type
         */
        public void setPrimaryModelType(ModelType primaryModelType) {
            if (primaryModelType != null) {
                this.primaryModelType = primaryModelType;
            }
        }

        /**
         * Returns the fallback model type.
         *
         * @return fallback model type
         */
        public ModelType getFallbackModelType() {
            return fallbackModelType;
        }

        /**
         * Updates the fallback model type.
         *
         * @param fallbackModelType fallback model type
         */
        public void setFallbackModelType(ModelType fallbackModelType) {
            if (fallbackModelType != null) {
                this.fallbackModelType = fallbackModelType;
            }
        }
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
