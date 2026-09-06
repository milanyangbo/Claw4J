package com.claw4j.common.dto;

import com.claw4j.common.exception.ErrorCode;
import com.claw4j.common.util.IdUtil;
import java.util.Objects;

/**
 * Standard response envelope for Claw4J API errors.
 */
public final class ErrorResponse {

    private final boolean success;
    private final String code;
    private final String message;
    private final String requestId;

    private ErrorResponse(boolean success, String code, String message, String requestId) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.requestId = requestId;
    }

    /**
     * Creates an error response for the supplied error code.
     *
     * @param errorCode shared error-code contract
     * @param message external-safe response message
     * @param requestId request identifier for tracing
     * @return error response envelope
     */
    public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
        ErrorCode resolvedErrorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        String resolvedMessage = isBlank(message) ? resolvedErrorCode.getMessage() : message;
        String resolvedRequestId = isBlank(requestId) ? IdUtil.generate() : requestId;
        return new ErrorResponse(false, resolvedErrorCode.getCode(), resolvedMessage, resolvedRequestId);
    }

    /**
     * Returns whether the request succeeded.
     *
     * @return false for error responses
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the stable error code.
     *
     * @return shared error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the external-safe error message.
     *
     * @return error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the request identifier used for support tracing.
     *
     * @return request identifier
     */
    public String getRequestId() {
        return requestId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
