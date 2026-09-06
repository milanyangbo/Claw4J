package com.claw4j.common.dto;

import com.claw4j.common.constant.CommonConstants;

/**
 * Standard response envelope for successful Claw4J API calls.
 *
 * @param <T> payload type returned by the API
 */
public final class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /**
     * Creates a standard success response with the default success message.
     *
     * @param data response payload
     * @param <T> response payload type
     * @return success response envelope
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, CommonConstants.DEFAULT_SUCCESS_MESSAGE, data);
    }

    /**
     * Returns whether the request succeeded.
     *
     * @return true when the response represents success
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the response message.
     *
     * @return response message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the response payload.
     *
     * @return response payload
     */
    public T getData() {
        return data;
    }
}
