package com.claw4j.orchestrator.service;

import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.orchestrator.dto.StreamingRequestContext;

/**
 * Streams model output tokens for the Orchestrator model path.
 */
public interface ModelStreamClient {

    String STATUS_PRIMARY_INTERRUPTED = "PRIMARY_INTERRUPTED";
    String STATUS_PRIMARY_TTFB_TIMEOUT = "PRIMARY_TTFB_TIMEOUT";
    String STATUS_PRIMARY_PROVIDER_FAILURE = "PRIMARY_PROVIDER_FAILURE";
    String STATUS_PRIMARY_CIRCUIT_OPEN = "PRIMARY_CIRCUIT_OPEN";

    /**
     * Streams tokens from the selected model into the token consumer.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for model invocation
     * @param request business request
     * @param context Header-derived request context
     * @param tokenConsumer consumer that receives raw model tokens
     */
    void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
            StreamingRequestContext context,
            TokenConsumer tokenConsumer
    );

    /**
     * Consumes a single raw model token.
     */
    @FunctionalInterface
    interface TokenConsumer {

        /**
         * Accepts one raw model token.
         *
         * @param token raw model token
         */
        void accept(String token);
    }

    /**
     * Signals model stream interruption.
     */
    final class ModelStreamException extends RuntimeException {

        private final boolean beforeFirstToken;
        private final String statusCode;

        /**
         * Creates a model stream exception.
         *
         * @param message external-safe failure message
         * @param beforeFirstToken true when interruption happened before any token
         */
        public ModelStreamException(String message, boolean beforeFirstToken) {
            this(message, beforeFirstToken, defaultStatus(beforeFirstToken));
        }

        /**
         * Creates a model stream exception with a stable fallback status.
         *
         * @param message external-safe failure message
         * @param beforeFirstToken true when interruption happened before any token
         * @param statusCode stable fallback status code
         */
        public ModelStreamException(String message, boolean beforeFirstToken, String statusCode) {
            this(message, beforeFirstToken, statusCode, null);
        }

        /**
         * Creates a model stream exception with a stable fallback status and cause.
         *
         * @param message external-safe failure message
         * @param beforeFirstToken true when interruption happened before any token
         * @param statusCode stable fallback status code
         * @param cause sanitized cause retained for diagnostics
         */
        public ModelStreamException(
                String message,
                boolean beforeFirstToken,
                String statusCode,
                Throwable cause
        ) {
            super(message, cause);
            this.beforeFirstToken = beforeFirstToken;
            this.statusCode = requireText(statusCode);
        }

        /**
         * Returns whether the interruption happened before the first token.
         *
         * @return true when no token was emitted before failure
         */
        public boolean isBeforeFirstToken() {
            return beforeFirstToken;
        }

        /**
         * Returns the stable stream fallback status code.
         *
         * @return stable status code
         */
        public String getStatusCode() {
            return statusCode;
        }

        private static String defaultStatus(boolean beforeFirstToken) {
            if (beforeFirstToken) {
                return STATUS_PRIMARY_TTFB_TIMEOUT;
            }
            return STATUS_PRIMARY_INTERRUPTED;
        }

        private static String requireText(String value) {
            if (value == null || value.isBlank()) {
                return STATUS_PRIMARY_PROVIDER_FAILURE;
            }
            return value;
        }
    }
}
