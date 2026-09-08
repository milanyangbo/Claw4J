package com.claw4j.orchestrator.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import java.util.Optional;

/**
 * Header-derived request context for streaming model sessions.
 */
public final class StreamingRequestContext {

    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String TENANT_ID_FIELD = "tenantId";
    private static final String USER_ID_FIELD = "userId";
    private static final String IDEMPOTENCY_KEY_FIELD = "idempotencyKey";
    private static final String SESSION_ID_FIELD = "sessionId";
    private static final String LAST_EVENT_ID_FIELD = "lastEventId";
    private static final String REQUIRED_FIELD_SUFFIX = " header is required";

    private final String requestId;
    private final String tenantId;
    private final String userId;
    private final String idempotencyKey;
    private final String sessionId;
    private final Optional<Long> lastEventId;

    private StreamingRequestContext(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            Optional<Long> lastEventId
    ) {
        this.requestId = requireText(requestId, REQUEST_ID_FIELD);
        this.tenantId = requireText(tenantId, TENANT_ID_FIELD);
        this.userId = requireText(userId, USER_ID_FIELD);
        this.idempotencyKey = requireText(idempotencyKey, IDEMPOTENCY_KEY_FIELD);
        this.sessionId = requireText(sessionId, SESSION_ID_FIELD);
        this.lastEventId = requireOptional(lastEventId);
    }

    /**
     * Creates a validated request context from HTTP Header values.
     *
     * @param requestId request id Header value
     * @param tenantId tenant id Header value
     * @param userId user id Header value
     * @param idempotencyKey idempotency key Header value
     * @param sessionId stream session id Header value
     * @param lastEventId last SSE event id Header value
     * @return validated streaming request context
     */
    public static StreamingRequestContext fromHeaders(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            String lastEventId
    ) {
        return new StreamingRequestContext(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                parseLastEventId(lastEventId)
        );
    }

    /**
     * Creates a validated request context without a reconnect event id.
     *
     * @param requestId request id
     * @param tenantId tenant id
     * @param userId user id
     * @param idempotencyKey idempotency key
     * @param sessionId stream session id
     * @return validated streaming request context
     */
    public static StreamingRequestContext initial(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId
    ) {
        return new StreamingRequestContext(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                Optional.empty()
        );
    }

    /**
     * Creates a validated request context with a reconnect event id.
     *
     * @param requestId request id
     * @param tenantId tenant id
     * @param userId user id
     * @param idempotencyKey idempotency key
     * @param sessionId stream session id
     * @param lastEventId last SSE event id
     * @return validated streaming request context
     */
    public static StreamingRequestContext reconnect(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            long lastEventId
    ) {
        if (lastEventId < 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, LAST_EVENT_ID_FIELD + " must not be negative");
        }
        return new StreamingRequestContext(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                Optional.of(lastEventId)
        );
    }

    /**
     * Returns the request id.
     *
     * @return request id
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the tenant id.
     *
     * @return tenant id
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the user id.
     *
     * @return user id
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
     * Returns the stream session id.
     *
     * @return stream session id
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the optional last event id for SSE reconnect.
     *
     * @return optional last event id
     */
    public Optional<Long> getLastEventId() {
        return lastEventId;
    }

    private static Optional<Long> parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(lastEventId);
            if (parsed < 0L) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, LAST_EVENT_ID_FIELD + " must not be negative");
            }
            return Optional.of(parsed);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, LAST_EVENT_ID_FIELD + " must be numeric");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }

    private static Optional<Long> requireOptional(Optional<Long> value) {
        if (value == null) {
            return Optional.empty();
        }
        return value;
    }
}
