package com.claw4j.common.exception;

import java.util.Objects;

/**
 * Base exception carrying a stable Claw4J error code.
 */
public class Claw4jException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Creates an exception with the default message from the error code.
     *
     * @param errorCode shared error-code contract
     */
    public Claw4jException(ErrorCode errorCode) {
        this(errorCode, requireErrorCode(errorCode).getMessage());
    }

    /**
     * Creates an exception with a custom external-safe message.
     *
     * @param errorCode shared error-code contract
     * @param message external-safe error message
     */
    public Claw4jException(ErrorCode errorCode, String message) {
        super(resolveMessage(errorCode, message));
        this.errorCode = requireErrorCode(errorCode);
    }

    /**
     * Creates an exception with a custom message and root cause.
     *
     * @param errorCode shared error-code contract
     * @param message external-safe error message
     * @param cause root cause for diagnostics
     */
    public Claw4jException(ErrorCode errorCode, String message, Throwable cause) {
        super(resolveMessage(errorCode, message), cause);
        this.errorCode = requireErrorCode(errorCode);
    }

    /**
     * Returns the shared error-code contract.
     *
     * @return shared error code
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    private static String resolveMessage(ErrorCode errorCode, String message) {
        ErrorCode resolvedErrorCode = requireErrorCode(errorCode);
        if (message == null || message.isBlank()) {
            return resolvedErrorCode.getMessage();
        }
        return message;
    }
}
