package com.claw4j.orchestrator.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Event payload emitted by the Orchestrator streaming model proof path.
 */
public final class ModelStreamEvent {

    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_FALLBACK_START = "fallback-start";
    public static final String EVENT_ADAPTATION = "adaptation";
    public static final String EVENT_COMPLETE = "complete";
    public static final String EVENT_FAILURE = "failure";

    private static final String EVENT_ID_FIELD = "eventId";
    private static final String EVENT_NAME_FIELD = "eventName";
    private static final String SESSION_ID_FIELD = "sessionId";
    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String CONTENT_FIELD = "content";
    private static final String MODEL_TYPE_FIELD = "modelType";
    private static final String STATUS_CODE_FIELD = "statusCode";
    private static final String REQUIRED_FIELD_SUFFIX = " is required";

    private final long eventId;
    private final String eventName;
    private final String sessionId;
    private final String requestId;
    private final String content;
    private final ModelType modelType;
    private final String statusCode;

    /**
     * Creates a model stream event.
     *
     * @param eventId monotonically increasing event id
     * @param eventName SSE event name
     * @param sessionId stream session id
     * @param requestId request id for tracing
     * @param content external-safe event content
     * @param modelType model family associated with the event
     * @param statusCode stable status or error code
     */
    @JsonCreator
    public ModelStreamEvent(
            @JsonProperty(EVENT_ID_FIELD) long eventId,
            @JsonProperty(EVENT_NAME_FIELD) String eventName,
            @JsonProperty(SESSION_ID_FIELD) String sessionId,
            @JsonProperty(REQUEST_ID_FIELD) String requestId,
            @JsonProperty(CONTENT_FIELD) String content,
            @JsonProperty(MODEL_TYPE_FIELD) ModelType modelType,
            @JsonProperty(STATUS_CODE_FIELD) String statusCode
    ) {
        this.eventId = requirePositive(eventId, EVENT_ID_FIELD);
        this.eventName = requireText(eventName, EVENT_NAME_FIELD);
        this.sessionId = requireText(sessionId, SESSION_ID_FIELD);
        this.requestId = requireText(requestId, REQUEST_ID_FIELD);
        this.content = defaultIfMissing(content);
        this.modelType = Objects.requireNonNull(modelType, MODEL_TYPE_FIELD + REQUIRED_FIELD_SUFFIX);
        this.statusCode = defaultIfMissing(statusCode);
    }

    /**
     * Returns the event id.
     *
     * @return event id
     */
    public long getEventId() {
        return eventId;
    }

    /**
     * Returns the SSE event name.
     *
     * @return event name
     */
    public String getEventName() {
        return eventName;
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
     * Returns the request id.
     *
     * @return request id
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the external-safe event content.
     *
     * @return event content
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the model family associated with the event.
     *
     * @return model type
     */
    public ModelType getModelType() {
        return modelType;
    }

    /**
     * Returns the stable status or error code.
     *
     * @return status code
     */
    public String getStatusCode() {
        return statusCode;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + REQUIRED_FIELD_SUFFIX);
        }
        return value;
    }

    private static String defaultIfMissing(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}
