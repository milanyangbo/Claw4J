package com.claw4j.common.constant;

/**
 * Shared constants used by Claw4J modules.
 */
public final class CommonConstants {

    public static final String PROJECT_NAME = "Claw4J";
    public static final String DEFAULT_SUCCESS_MESSAGE = "success";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    public static final String REQUEST_ID_PREFIX = "req";
    public static final String DEFAULT_ID_PREFIX = "id";
    public static final String ID_SEPARATOR = "-";

    private CommonConstants() {
    }
}
