package com.claw4j.orchestrator.service;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.dto.ModelStreamEvent;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Bounded in-memory stream state store for single-instance resume paths.
 */
@Service
public class StreamingSessionStore {

    private final OrchestratorStreamingModelProperties properties;
    private final Clock clock;
    private final ConcurrentMap<SessionKey, StreamSession> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a streaming session store using the system clock.
     *
     * @param properties streaming model properties
     */
    public StreamingSessionStore(OrchestratorStreamingModelProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Creates a streaming session store using an explicit clock.
     *
     * @param properties streaming model properties
     * @param clock clock used for session retention checks
     */
    public StreamingSessionStore(OrchestratorStreamingModelProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Starts or resets a stream session for the validated owner context.
     *
     * @param context Header-derived request context
     */
    public void start(StreamingRequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        sessions.put(SessionKey.from(context), new StreamSession(clock.instant()));
    }

    /**
     * Appends a stream event to the validated session.
     *
     * @param context Header-derived request context
     * @param eventName SSE event name
     * @param content external-safe event content
     * @param modelType model family associated with the event
     * @param statusCode stable status or error code
     * @return stored stream event with assigned event id
     */
    public ModelStreamEvent append(
            StreamingRequestContext context,
            String eventName,
            String content,
            ModelType modelType,
            String statusCode
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(modelType, "modelType must not be null");
        StreamSession session = activeSession(context);
        synchronized (session) {
            ModelStreamEvent event = new ModelStreamEvent(
                    session.nextEventId(),
                    eventName,
                    context.getSessionId(),
                    context.getRequestId(),
                    content,
                    modelType,
                    statusCode
            );
            session.events.add(event);
            session.updatedAt = clock.instant();
            trimSession(session);
            return event;
        }
    }

    /**
     * Replays buffered events after the requested last event id.
     *
     * @param context Header-derived request context
     * @param lastEventId last event id received by the client
     * @return replayable events after the requested event id
     */
    public List<ModelStreamEvent> replayAfter(StreamingRequestContext context, long lastEventId) {
        Objects.requireNonNull(context, "context must not be null");
        if (lastEventId < 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "lastEventId must not be negative");
        }
        StreamSession session = activeSession(context);
        synchronized (session) {
            if (session.events.isEmpty()) {
                return List.of();
            }
            long firstRetainedEventId = session.events.get(0).getEventId();
            if (lastEventId < firstRetainedEventId - 1L) {
                throw new BusinessException(ErrorCode.STREAM_RESUME_EXPIRED);
            }
            return session.events.stream()
                    .filter(event -> event.getEventId() > lastEventId)
                    .toList();
        }
    }

    /**
     * Returns cached user-visible token content for fallback continuation.
     *
     * @param context Header-derived request context
     * @return cached user-visible output
     */
    public String cachedOutput(StreamingRequestContext context) {
        Objects.requireNonNull(context, "context must not be null");
        StreamSession session = activeSession(context);
        synchronized (session) {
            StringBuilder cachedOutput = new StringBuilder();
            for (ModelStreamEvent event : session.events) {
                if (ModelStreamEvent.EVENT_TOKEN.equals(event.getEventName())) {
                    cachedOutput.append(event.getContent());
                }
            }
            return cachedOutput.toString();
        }
    }

    /**
     * Expires sessions older than the configured retention window.
     *
     * @return number of expired sessions
     */
    public int expireSessions() {
        Instant now = clock.instant();
        int expiredCount = 0;
        Iterator<SessionKey> iterator = sessions.keySet().iterator();
        while (iterator.hasNext()) {
            SessionKey key = iterator.next();
            StreamSession session = sessions.get(key);
            if (session != null && isExpired(session, now)) {
                iterator.remove();
                expiredCount++;
            }
        }
        return expiredCount;
    }

    private StreamSession activeSession(StreamingRequestContext context) {
        expireSessions();
        StreamSession session = sessions.get(SessionKey.from(context));
        if (session == null) {
            throw new BusinessException(ErrorCode.STREAM_RESUME_EXPIRED);
        }
        return session;
    }

    private void trimSession(StreamSession session) {
        while (bufferedTokenContentLength(session.events) > properties.getFallback().getResumeBufferSize()
                && !session.events.isEmpty()) {
            session.events.remove(0);
        }
    }

    private boolean isExpired(StreamSession session, Instant now) {
        Instant expiresAt = session.updatedAt.plus(properties.getFallback().getSessionRetention());
        return !expiresAt.isAfter(now);
    }

    private static int bufferedTokenContentLength(List<ModelStreamEvent> events) {
        int contentLength = 0;
        for (ModelStreamEvent event : events) {
            if (ModelStreamEvent.EVENT_TOKEN.equals(event.getEventName())) {
                contentLength += event.getContent().length();
            }
        }
        return contentLength;
    }

    private record SessionKey(String tenantId, String userId, String sessionId) {

        private static SessionKey from(StreamingRequestContext context) {
            return new SessionKey(context.getTenantId(), context.getUserId(), context.getSessionId());
        }
    }

    private static final class StreamSession {

        private final Instant createdAt;
        private final List<ModelStreamEvent> events = new ArrayList<>();
        private Instant updatedAt;
        private long nextEventId = 1L;

        private StreamSession(Instant createdAt) {
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private long nextEventId() {
            long eventId = nextEventId;
            nextEventId++;
            return eventId;
        }
    }
}
