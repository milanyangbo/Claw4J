package com.claw4j.orchestrator.config;

import com.claw4j.orchestrator.dto.ModelType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds Orchestrator streaming model proof-path configuration.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.model.streaming")
public class OrchestratorStreamingModelProperties {

    private static final Duration DEFAULT_TTFB_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SESSION_RETENTION = Duration.ofMinutes(10);
    private static final int DEFAULT_RESUME_BUFFER_SIZE = 10_000;
    private static final int DEFAULT_PRIMARY_MAX_TOKENS = 128_000;
    private static final int DEFAULT_FALLBACK_MAX_TOKENS = 32_000;
    private static final String DEFAULT_PRIMARY_SUCCESS_CONTENT = "primary model response";
    private static final String DEFAULT_PRIMARY_FAILURE_PREFIX = "partial primary response ";
    private static final String DEFAULT_FALLBACK_CONTINUATION = "continued by fallback model";
    private static final String DEFAULT_MALFORMED_OUTPUT = "<think>internal reasoning only</think>";

    private final Fallback fallback = new Fallback();
    private final Context context = new Context();
    private final Parser parser = new Parser();
    private final ProofClient proofClient = new ProofClient();

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
     * Returns deterministic proof client configuration.
     *
     * @return proof client configuration
     */
    public ProofClient getProofClient() {
        return proofClient;
    }

    /**
     * Context adaptation strategies supported by the proof path.
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
     * Deterministic proof client behavior configuration.
     */
    public static final class ProofClient {

        private ModelType primaryModelType = ModelType.DEEPSEEK;
        private ModelType fallbackModelType = ModelType.QWEN;
        private String primarySuccessContent = DEFAULT_PRIMARY_SUCCESS_CONTENT;
        private String primaryFailurePrefix = DEFAULT_PRIMARY_FAILURE_PREFIX;
        private String fallbackContinuation = DEFAULT_FALLBACK_CONTINUATION;
        private String malformedOutput = DEFAULT_MALFORMED_OUTPUT;

        /**
         * Returns the deterministic primary model type.
         *
         * @return primary model type
         */
        public ModelType getPrimaryModelType() {
            return primaryModelType;
        }

        /**
         * Updates the deterministic primary model type.
         *
         * @param primaryModelType primary model type
         */
        public void setPrimaryModelType(ModelType primaryModelType) {
            if (primaryModelType != null) {
                this.primaryModelType = primaryModelType;
            }
        }

        /**
         * Returns the deterministic fallback model type.
         *
         * @return fallback model type
         */
        public ModelType getFallbackModelType() {
            return fallbackModelType;
        }

        /**
         * Updates the deterministic fallback model type.
         *
         * @param fallbackModelType fallback model type
         */
        public void setFallbackModelType(ModelType fallbackModelType) {
            if (fallbackModelType != null) {
                this.fallbackModelType = fallbackModelType;
            }
        }

        /**
         * Returns the primary success content used by the proof client.
         *
         * @return primary success content
         */
        public String getPrimarySuccessContent() {
            return primarySuccessContent;
        }

        /**
         * Updates the primary success content used by the proof client.
         *
         * @param primarySuccessContent primary success content
         */
        public void setPrimarySuccessContent(String primarySuccessContent) {
            this.primarySuccessContent = defaultIfBlank(primarySuccessContent, DEFAULT_PRIMARY_SUCCESS_CONTENT);
        }

        /**
         * Returns the partial primary output emitted before a simulated failure.
         *
         * @return partial primary output
         */
        public String getPrimaryFailurePrefix() {
            return primaryFailurePrefix;
        }

        /**
         * Updates the partial primary output emitted before a simulated failure.
         *
         * @param primaryFailurePrefix partial primary output
         */
        public void setPrimaryFailurePrefix(String primaryFailurePrefix) {
            this.primaryFailurePrefix = defaultIfBlank(primaryFailurePrefix, DEFAULT_PRIMARY_FAILURE_PREFIX);
        }

        /**
         * Returns the fallback continuation text used by the proof client.
         *
         * @return fallback continuation text
         */
        public String getFallbackContinuation() {
            return fallbackContinuation;
        }

        /**
         * Updates the fallback continuation text used by the proof client.
         *
         * @param fallbackContinuation fallback continuation text
         */
        public void setFallbackContinuation(String fallbackContinuation) {
            this.fallbackContinuation = defaultIfBlank(fallbackContinuation, DEFAULT_FALLBACK_CONTINUATION);
        }

        /**
         * Returns the malformed output used by the proof client.
         *
         * @return malformed output
         */
        public String getMalformedOutput() {
            return malformedOutput;
        }

        /**
         * Updates the malformed output used by the proof client.
         *
         * @param malformedOutput malformed output
         */
        public void setMalformedOutput(String malformedOutput) {
            this.malformedOutput = defaultIfBlank(malformedOutput, DEFAULT_MALFORMED_OUTPUT);
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

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
