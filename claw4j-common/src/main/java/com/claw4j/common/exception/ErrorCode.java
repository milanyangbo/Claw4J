package com.claw4j.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable error-code catalog shared by all Claw4J services.
 */
public enum ErrorCode {

    INVALID_REQUEST("CLAW4J-400", "Invalid request", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("CLAW4J-500", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    DOWNSTREAM_SERVICE_UNAVAILABLE("CLAW4J-RPC-001", "Downstream service unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    RATE_LIMITED("CLAW4J-SENTINEL-429", "Request rate limit exceeded", HttpStatus.TOO_MANY_REQUESTS),
    CIRCUIT_OPEN("CLAW4J-SENTINEL-503", "Circuit breaker is open", HttpStatus.SERVICE_UNAVAILABLE),
    STREAM_RESUME_EXPIRED("CLAW4J-STREAM-410", "Streaming resume state expired", HttpStatus.GONE),
    MODEL_CONTEXT_TOO_LARGE("CLAW4J-MODEL-413", "Model context is too large", HttpStatus.CONTENT_TOO_LARGE),
    MODEL_OUTPUT_PARSER_FAILURE("CLAW4J-MODEL-422", "Model output parser failed", HttpStatus.UNPROCESSABLE_CONTENT),
    MODEL_PROVIDER_CONFIGURATION_INVALID(
            "CLAW4J-MODEL-500",
            "Model provider configuration is invalid",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    JSON_SERIALIZATION_ERROR("CLAW4J-JSON-001", "JSON serialization failed", HttpStatus.INTERNAL_SERVER_ERROR),
    JSON_DESERIALIZATION_ERROR("CLAW4J-JSON-002", "JSON deserialization failed", HttpStatus.BAD_REQUEST),
    INVALID_ID_PREFIX("CLAW4J-ID-001", "Identifier prefix is invalid", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the stable machine-readable code.
     *
     * @return stable error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the external-safe default message.
     *
     * @return default error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the HTTP status used for response mapping.
     *
     * @return HTTP status category
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
