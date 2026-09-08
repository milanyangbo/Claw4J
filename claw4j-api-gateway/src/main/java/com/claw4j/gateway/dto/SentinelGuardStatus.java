package com.claw4j.gateway.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Gateway-local DTO describing a successful Sentinel-protected request.
 */
public final class SentinelGuardStatus {

    private static final String RESOURCE_NAME_FIELD = "resourceName";
    private static final String TENANT_ID_FIELD = "tenantId";
    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String USER_ID_FIELD = "userId";
    private static final String IDEMPOTENCY_KEY_FIELD = "idempotencyKey";
    private static final String GUARD_RESULT_FIELD = "guardResult";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final String resourceName;
    private final String tenantId;
    private final String requestId;
    private final String userId;
    private final String idempotencyKey;
    private final String guardResult;

    /**
     * Creates a validated Sentinel guard status.
     *
     * @param resourceName protected resource name
     * @param tenantId tenant identifier
     * @param requestId request identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @param guardResult guard result label
     */
    @JsonCreator
    public SentinelGuardStatus(
            @JsonProperty(RESOURCE_NAME_FIELD) String resourceName,
            @JsonProperty(TENANT_ID_FIELD) String tenantId,
            @JsonProperty(REQUEST_ID_FIELD) String requestId,
            @JsonProperty(USER_ID_FIELD) String userId,
            @JsonProperty(IDEMPOTENCY_KEY_FIELD) String idempotencyKey,
            @JsonProperty(GUARD_RESULT_FIELD) String guardResult
    ) {
        this.resourceName = requireText(resourceName, RESOURCE_NAME_FIELD);
        this.tenantId = requireText(tenantId, TENANT_ID_FIELD);
        this.requestId = requireText(requestId, REQUEST_ID_FIELD);
        this.userId = requireText(userId, USER_ID_FIELD);
        this.idempotencyKey = requireText(idempotencyKey, IDEMPOTENCY_KEY_FIELD);
        this.guardResult = requireText(guardResult, GUARD_RESULT_FIELD);
    }

    /**
     * Creates an allowed Sentinel guard status.
     *
     * @param resourceName protected resource name
     * @param tenantId tenant identifier
     * @param requestId request identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @return allowed guard status
     */
    public static SentinelGuardStatus allowed(
            String resourceName,
            String tenantId,
            String requestId,
            String userId,
            String idempotencyKey
    ) {
        return new SentinelGuardStatus(resourceName, tenantId, requestId, userId, idempotencyKey, "allowed");
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
     * Returns the tenant identifier.
     *
     * @return tenant identifier
     */
    public String getTenantId() {
        return tenantId;
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
     * Returns the user identifier.
     *
     * @return user identifier
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the idempotency key.
     *
     * @return idempotency key
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the Sentinel guard result.
     *
     * @return guard result label
     */
    public String getGuardResult() {
        return guardResult;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }
}
