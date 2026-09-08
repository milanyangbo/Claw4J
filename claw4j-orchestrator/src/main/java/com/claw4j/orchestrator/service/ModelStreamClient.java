package com.claw4j.orchestrator.service;

import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.common.dto.StreamingModelRequest;

/**
 * Streams model output tokens for the Orchestrator proof path.
 */
public interface ModelStreamClient {

    /**
     * Streams tokens from the selected model into the token consumer.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for model invocation
     * @param request business request controls for the proof path
     * @param tokenConsumer consumer that receives raw model tokens
     */
    void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
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
     * Signals deterministic model stream interruption.
     */
    final class ModelStreamException extends RuntimeException {

        private final boolean beforeFirstToken;

        /**
         * Creates a model stream exception.
         *
         * @param message external-safe failure message
         * @param beforeFirstToken true when interruption happened before any token
         */
        public ModelStreamException(String message, boolean beforeFirstToken) {
            super(message);
            this.beforeFirstToken = beforeFirstToken;
        }

        /**
         * Returns whether the interruption happened before the first token.
         *
         * @return true when no token was emitted before failure
         */
        public boolean isBeforeFirstToken() {
            return beforeFirstToken;
        }
    }
}
