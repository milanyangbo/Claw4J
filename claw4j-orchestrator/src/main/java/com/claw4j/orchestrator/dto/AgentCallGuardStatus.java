package com.claw4j.orchestrator.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Orchestrator-local DTO describing a Sentinel-protected Agent-call result.
 */
public final class AgentCallGuardStatus {

    private static final String RESOURCE_NAME_FIELD = "resourceName";
    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String TENANT_ID_FIELD = "tenantId";
    private static final String FALLBACK_APPLIED_FIELD = "fallbackApplied";
    private static final String RESULT_MESSAGE_FIELD = "resultMessage";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final String resourceName;
    private final String requestId;
    private final String tenantId;
    private final boolean fallbackApplied;
    private final String resultMessage;

    /**
     * Creates a validated Agent-call guard status.
     *
     * @param resourceName protected resource name
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param fallbackApplied whether fallback was applied
     * @param resultMessage result message
     */
    @JsonCreator
    public AgentCallGuardStatus(
            @JsonProperty(RESOURCE_NAME_FIELD) String resourceName,
            @JsonProperty(REQUEST_ID_FIELD) String requestId,
            @JsonProperty(TENANT_ID_FIELD) String tenantId,
            @JsonProperty(FALLBACK_APPLIED_FIELD) boolean fallbackApplied,
            @JsonProperty(RESULT_MESSAGE_FIELD) String resultMessage
    ) {
        this.resourceName = requireText(resourceName, RESOURCE_NAME_FIELD);
        this.requestId = requireText(requestId, REQUEST_ID_FIELD);
        this.tenantId = requireText(tenantId, TENANT_ID_FIELD);
        this.fallbackApplied = fallbackApplied;
        this.resultMessage = requireText(resultMessage, RESULT_MESSAGE_FIELD);
    }

    /**
     * Creates a successful Agent-call guard status.
     *
     * @param resourceName protected resource name
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @return successful Agent-call status
     */
    public static AgentCallGuardStatus success(String resourceName, String requestId, String tenantId) {
        return new AgentCallGuardStatus(resourceName, requestId, tenantId, false, "agent-call-success");
    }

    /**
     * Creates a fallback Agent-call guard status.
     *
     * @param resourceName protected resource name
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @return fallback Agent-call status
     */
    public static AgentCallGuardStatus fallback(String resourceName, String requestId, String tenantId) {
        return new AgentCallGuardStatus(
                resourceName,
                requestId,
                tenantId,
                true,
                ErrorCode.CIRCUIT_OPEN.getCode() + ": " + ErrorCode.CIRCUIT_OPEN.getMessage()
        );
    }

    /**
     * Returns the protected resource name.
     *
     * @return protected resource name
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * Returns the request identifier.
     *
     * @return request identifier
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the tenant identifier.
     *
     * @return tenant identifier
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns whether fallback was applied.
     *
     * @return true when fallback is applied
     */
    public boolean isFallbackApplied() {
        return fallbackApplied;
    }

    /**
     * Returns the result message.
     *
     * @return result message
     */
    public String getResultMessage() {
        return resultMessage;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }
}
