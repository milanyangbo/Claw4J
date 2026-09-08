package com.claw4j.common.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Business payload for the streaming model proof endpoint.
 */
public final class StreamingModelRequest {

    private static final String QUERY_FIELD = "query";
    private static final String SIMULATE_PRIMARY_FAILURE_FIELD = "simulatePrimaryFailure";
    private static final String SIMULATE_PRIMARY_TTFB_TIMEOUT_FIELD = "simulatePrimaryTtfbTimeout";
    private static final String SIMULATE_MALFORMED_OUTPUT_FIELD = "simulateMalformedOutput";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final String query;
    private final boolean simulatePrimaryFailure;
    private final boolean simulatePrimaryTtfbTimeout;
    private final boolean simulateMalformedOutput;

    /**
     * Creates a streaming model business request.
     *
     * @param query original user query
     * @param simulatePrimaryFailure whether to simulate a mid-stream primary failure
     * @param simulatePrimaryTtfbTimeout whether to simulate a primary time-to-first-byte timeout
     * @param simulateMalformedOutput whether to simulate malformed model output
     */
    @JsonCreator
    public StreamingModelRequest(
            @JsonProperty(QUERY_FIELD) String query,
            @JsonProperty(SIMULATE_PRIMARY_FAILURE_FIELD) boolean simulatePrimaryFailure,
            @JsonProperty(SIMULATE_PRIMARY_TTFB_TIMEOUT_FIELD) boolean simulatePrimaryTtfbTimeout,
            @JsonProperty(SIMULATE_MALFORMED_OUTPUT_FIELD) boolean simulateMalformedOutput
    ) {
        this.query = requireText(query, QUERY_FIELD);
        this.simulatePrimaryFailure = simulatePrimaryFailure;
        this.simulatePrimaryTtfbTimeout = simulatePrimaryTtfbTimeout;
        this.simulateMalformedOutput = simulateMalformedOutput;
    }

    /**
     * Creates a streaming request with no simulated failure modes.
     *
     * @param query original user query
     * @return streaming model request
     */
    public static StreamingModelRequest of(String query) {
        return new StreamingModelRequest(query, false, false, false);
    }

    /**
     * Returns the original user query.
     *
     * @return original user query
     */
    public String getQuery() {
        return query;
    }

    /**
     * Returns whether primary failure should be simulated.
     *
     * @return true when primary failure should be simulated
     */
    public boolean isSimulatePrimaryFailure() {
        return simulatePrimaryFailure;
    }

    /**
     * Returns whether primary TTFB timeout should be simulated.
     *
     * @return true when primary TTFB timeout should be simulated
     */
    public boolean isSimulatePrimaryTtfbTimeout() {
        return simulatePrimaryTtfbTimeout;
    }

    /**
     * Returns whether malformed output should be simulated.
     *
     * @return true when malformed output should be simulated
     */
    public boolean isSimulateMalformedOutput() {
        return simulateMalformedOutput;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }
}
