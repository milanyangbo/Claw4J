package com.claw4j.common.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Business payload for streaming model requests.
 */
public final class StreamingModelRequest {

    private static final String QUERY_FIELD = "query";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final String query;

    /**
     * Creates a streaming model business request.
     *
     * @param query original user query
     */
    @JsonCreator
    public StreamingModelRequest(@JsonProperty(QUERY_FIELD) String query) {
        this.query = requireText(query, QUERY_FIELD);
    }

    /**
     * Creates a streaming request.
     *
     * @param query original user query
     * @return streaming model request
     */
    public static StreamingModelRequest of(String query) {
        return new StreamingModelRequest(query);
    }

    /**
     * Returns the original user query.
     *
     * @return original user query
     */
    public String getQuery() {
        return query;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }
}
