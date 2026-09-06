package com.claw4j.common.exception;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ErrorResponse;
import com.claw4j.common.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Converts Claw4J exceptions into consistent external API error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles expected Claw4J exceptions with their stable error code.
     *
     * @param exception known Claw4J exception
     * @param request current web request
     * @return response entity containing the shared error response body
     */
    @ExceptionHandler(Claw4jException.class)
    public ResponseEntity<ErrorResponse> handleClaw4jException(Claw4jException exception, WebRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        LOGGER.warn("Handled Claw4J exception with code {}", errorCode.getCode());
        ErrorResponse response = ErrorResponse.of(errorCode, exception.getMessage(), resolveRequestId(request));
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    /**
     * Handles unexpected exceptions without exposing implementation details.
     *
     * @param exception unexpected exception
     * @param request current web request
     * @return response entity containing a sanitized internal-error body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception, WebRequest request) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        LOGGER.error("Unexpected exception handled by common exception handler, type={}", exception.getClass().getName());
        ErrorResponse response = ErrorResponse.of(errorCode, errorCode.getMessage(), resolveRequestId(request));
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    private String resolveRequestId(WebRequest request) {
        if (request == null) {
            return IdUtil.generate(CommonConstants.REQUEST_ID_PREFIX);
        }
        String requestId = request.getHeader(CommonConstants.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return IdUtil.generate(CommonConstants.REQUEST_ID_PREFIX);
        }
        return requestId;
    }
}
