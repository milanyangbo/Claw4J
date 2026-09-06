package com.claw4j.common.exception;

/**
 * Exception type for expected business-rule failures.
 */
public class BusinessException extends Claw4jException {

    /**
     * Creates a business exception with the default message from the error code.
     *
     * @param errorCode shared error-code contract
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * Creates a business exception with a custom external-safe message.
     *
     * @param errorCode shared error-code contract
     * @param message external-safe error message
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
