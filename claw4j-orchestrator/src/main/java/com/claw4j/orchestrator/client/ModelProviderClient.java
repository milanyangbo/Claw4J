package com.claw4j.orchestrator.client;

import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import com.claw4j.orchestrator.service.ModelStreamClient;

/**
 * Outbound boundary for provider-backed model streaming calls.
 */
public interface ModelProviderClient {

    /**
     * Streams raw provider tokens from the selected model.
     *
     * @param modelType model family to invoke
     * @param prompt adapted prompt for provider invocation
     * @param request validated business streaming request
     * @param context validated Header-derived request context
     * @param tokenConsumer token callback for raw provider output
     */
    void stream(
            ModelType modelType,
            String prompt,
            StreamingModelRequest request,
            StreamingRequestContext context,
            ModelStreamClient.TokenConsumer tokenConsumer
    );

    /**
     * Sanitized provider failure that avoids leaking transport details.
     */
    final class ModelProviderException extends RuntimeException {

        /**
         * Creates a sanitized provider failure.
         *
         * @param message external-safe failure message
         */
        public ModelProviderException(String message) {
            super(message);
        }

        /**
         * Creates a sanitized provider failure with a retained cause.
         *
         * @param message external-safe failure message
         * @param cause provider failure cause retained for diagnostics
         */
        public ModelProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
