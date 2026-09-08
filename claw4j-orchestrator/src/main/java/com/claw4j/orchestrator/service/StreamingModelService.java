package com.claw4j.orchestrator.service;

import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.config.OrchestratorStreamingModelProperties;
import com.claw4j.orchestrator.dto.ContextAdaptationStatus;
import com.claw4j.orchestrator.dto.ModelStreamEvent;
import com.claw4j.orchestrator.dto.ModelType;
import com.claw4j.orchestrator.dto.StandardModelOutput;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Orchestrates resilient streaming model output, fallback resume, and reconnect replay.
 */
@Service
public class StreamingModelService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingModelService.class);
    private static final String STATUS_OK = "OK";
    private static final String STATUS_RESUME_DISABLED = "RESUME_DISABLED";
    private static final String STATUS_FALLBACK_STARTED = "FALLBACK_STARTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_REPLAY = "REPLAY";
    private static final String STATUS_ADAPTED_PREFIX = "CONTEXT_";
    private static final String FALLBACK_STARTED_CONTENT = "fallback continuation started";
    private static final String STREAM_COMPLETED_CONTENT = "stream completed";
    private static final String RESUME_DISABLED_CONTENT = "streaming resume is disabled";
    private static final String CONTEXT_TOO_LARGE_CONTENT = "context exceeds target model budget";
    private static final String PARSER_FAILURE_CONTENT = "model output could not be parsed";
    private static final String RESUME_EXPIRED_CONTENT = "stream resume state is unavailable";

    private final OrchestratorStreamingModelProperties properties;
    private final ModelStreamClient modelStreamClient;
    private final ModelContextAdapter contextAdapter;
    private final ModelOutputParser outputParser;
    private final StreamingResumePromptBuilder resumePromptBuilder;
    private final StreamingSessionStore sessionStore;

    /**
     * Creates the streaming model service.
     *
     * @param properties streaming model properties
     * @param modelStreamClient model stream client
     * @param contextAdapter context adapter
     * @param outputParser output parser
     * @param resumePromptBuilder resume prompt builder
     * @param sessionStore streaming session store
     */
    public StreamingModelService(
            OrchestratorStreamingModelProperties properties,
            ModelStreamClient modelStreamClient,
            ModelContextAdapter contextAdapter,
            ModelOutputParser outputParser,
            StreamingResumePromptBuilder resumePromptBuilder,
            StreamingSessionStore sessionStore
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.modelStreamClient = Objects.requireNonNull(modelStreamClient, "modelStreamClient must not be null");
        this.contextAdapter = Objects.requireNonNull(contextAdapter, "contextAdapter must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
        this.resumePromptBuilder = Objects.requireNonNull(resumePromptBuilder, "resumePromptBuilder must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
    }

    /**
     * Opens an MVC SSE stream for the model proof path.
     *
     * @param request business streaming request
     * @param context Header-derived request context
     * @return SSE emitter that publishes stream events
     */
    public SseEmitter stream(StreamingModelRequest request, StreamingRequestContext context) {
        StreamingModelRequest validatedRequest = requireRequest(request);
        SseEmitter emitter = new SseEmitter(properties.getFallback().getTtfbTimeout().toMillis());
        CompletableFuture.runAsync(() -> sendEvents(emitter, streamEvents(validatedRequest, context)));
        return emitter;
    }

    /**
     * Executes the streaming proof path and returns deterministic events for tests.
     *
     * @param request business streaming request
     * @param context Header-derived request context
     * @return ordered stream events
     */
    public List<ModelStreamEvent> streamEvents(StreamingModelRequest request, StreamingRequestContext context) {
        StreamingModelRequest validatedRequest = requireRequest(request);
        Objects.requireNonNull(context, "context must not be null");
        Optional<Long> lastEventId = context.getLastEventId();
        if (lastEventId.isPresent()) {
            return replayEvents(context, lastEventId.get());
        }

        sessionStore.start(context);
        List<ModelStreamEvent> events = new ArrayList<>();
        ModelType primaryModelType = properties.getProofClient().getPrimaryModelType();
        ContextAdaptationStatus primaryContext = contextAdapter.adapt(validatedRequest.getQuery(), primaryModelType);
        if (primaryContext.isRejected()) {
            events.add(appendFailure(context, ErrorCode.MODEL_CONTEXT_TOO_LARGE, CONTEXT_TOO_LARGE_CONTENT));
            return events;
        }
        recordAdaptationIfNeeded(context, events, primaryContext);

        StringBuilder emittedContent = new StringBuilder();
        AtomicBoolean emittedAnyContent = new AtomicBoolean(false);
        try {
            modelStreamClient.stream(
                    primaryModelType,
                    primaryContext.getContent(),
                    validatedRequest,
                    context,
                    token -> emitVisibleToken(context, events, emittedContent, emittedAnyContent, primaryModelType, token)
            );
            if (!emittedAnyContent.get()) {
                events.add(appendFailure(context, ErrorCode.MODEL_OUTPUT_PARSER_FAILURE, PARSER_FAILURE_CONTENT));
                return events;
            }
            events.add(appendEvent(
                    context,
                    ModelStreamEvent.EVENT_COMPLETE,
                    STREAM_COMPLETED_CONTENT,
                    primaryModelType,
                    STATUS_COMPLETED
            ));
            return events;
        } catch (ModelStreamClient.ModelStreamException exception) {
            return continueWithFallback(validatedRequest, context, events, emittedContent, exception, emittedAnyContent);
        }
    }

    private List<ModelStreamEvent> replayEvents(StreamingRequestContext context, long lastEventId) {
        try {
            return sessionStore.replayAfter(context, lastEventId);
        } catch (BusinessException exception) {
            if (ErrorCode.STREAM_RESUME_EXPIRED == exception.getErrorCode()) {
                return List.of(new ModelStreamEvent(
                        1L,
                        ModelStreamEvent.EVENT_FAILURE,
                        context.getSessionId(),
                        context.getRequestId(),
                        RESUME_EXPIRED_CONTENT,
                        ModelType.GENERIC,
                        ErrorCode.STREAM_RESUME_EXPIRED.getCode()
                ));
            }
            throw exception;
        }
    }

    private List<ModelStreamEvent> continueWithFallback(
            StreamingModelRequest request,
            StreamingRequestContext context,
            List<ModelStreamEvent> events,
            StringBuilder emittedContent,
            ModelStreamClient.ModelStreamException exception,
            AtomicBoolean emittedAnyContent
    ) {
        ModelType primaryModelType = properties.getProofClient().getPrimaryModelType();
        if (!properties.getFallback().isResumeEnabled()) {
            events.add(appendEvent(
                    context,
                    ModelStreamEvent.EVENT_FAILURE,
                    RESUME_DISABLED_CONTENT,
                    primaryModelType,
                    STATUS_RESUME_DISABLED
            ));
            return events;
        }
        events.add(appendEvent(
                context,
                ModelStreamEvent.EVENT_FALLBACK_START,
                FALLBACK_STARTED_CONTENT,
                properties.getProofClient().getFallbackModelType(),
                fallbackStartStatus(exception)
        ));
        String resumePrompt = buildFallbackPrompt(request, context, emittedContent);
        ModelType fallbackModelType = properties.getProofClient().getFallbackModelType();
        ContextAdaptationStatus fallbackContext = contextAdapter.adapt(resumePrompt, fallbackModelType);
        if (fallbackContext.isRejected()) {
            events.add(appendFailure(context, ErrorCode.MODEL_CONTEXT_TOO_LARGE, CONTEXT_TOO_LARGE_CONTENT));
            return events;
        }
        recordAdaptationIfNeeded(context, events, fallbackContext);
        modelStreamClient.stream(
                fallbackModelType,
                fallbackContext.getContent(),
                request,
                context,
                token -> emitFallbackToken(context, events, emittedContent, emittedAnyContent, fallbackModelType, token)
        );
        if (!emittedAnyContent.get()) {
            events.add(appendFailure(context, ErrorCode.MODEL_OUTPUT_PARSER_FAILURE, PARSER_FAILURE_CONTENT));
            return events;
        }
        events.add(appendEvent(
                context,
                ModelStreamEvent.EVENT_COMPLETE,
                STREAM_COMPLETED_CONTENT,
                fallbackModelType,
                STATUS_COMPLETED
        ));
        return events;
    }

    private String buildFallbackPrompt(
            StreamingModelRequest request,
            StreamingRequestContext context,
            StringBuilder emittedContent
    ) {
        if (emittedContent.length() == 0) {
            return resumePromptBuilder.build(request.getQuery(), "");
        }
        return resumePromptBuilder.build(request.getQuery(), sessionStore.cachedOutput(context));
    }

    private void emitVisibleToken(
            StreamingRequestContext context,
            List<ModelStreamEvent> events,
            StringBuilder emittedContent,
            AtomicBoolean emittedAnyContent,
            ModelType modelType,
            String rawToken
    ) {
        Optional<StandardModelOutput> parsedToken = outputParser.filterStreamingToken(rawToken, modelType);
        if (parsedToken.isEmpty()) {
            return;
        }
        String visibleContent = parsedToken.get().getVisibleContent();
        if (visibleContent.isBlank()) {
            return;
        }
        events.add(appendEvent(context, ModelStreamEvent.EVENT_TOKEN, visibleContent, modelType, STATUS_OK));
        emittedContent.append(visibleContent);
        emittedAnyContent.set(true);
    }

    private void emitFallbackToken(
            StreamingRequestContext context,
            List<ModelStreamEvent> events,
            StringBuilder emittedContent,
            AtomicBoolean emittedAnyContent,
            ModelType modelType,
            String rawToken
    ) {
        Optional<StandardModelOutput> parsedToken = outputParser.filterStreamingToken(rawToken, modelType);
        if (parsedToken.isEmpty()) {
            return;
        }
        String visibleContent = trimDuplicatePrefix(parsedToken.get().getVisibleContent(), emittedContent.toString());
        if (visibleContent.isBlank()) {
            return;
        }
        events.add(appendEvent(context, ModelStreamEvent.EVENT_TOKEN, visibleContent, modelType, STATUS_OK));
        emittedContent.append(visibleContent);
        emittedAnyContent.set(true);
    }

    private void recordAdaptationIfNeeded(
            StreamingRequestContext context,
            List<ModelStreamEvent> events,
            ContextAdaptationStatus adaptationStatus
    ) {
        if (!adaptationStatus.isAdapted()) {
            return;
        }
        events.add(appendEvent(
                context,
                ModelStreamEvent.EVENT_ADAPTATION,
                adaptationStatus.getOutcome(),
                adaptationStatus.getModelType(),
                STATUS_ADAPTED_PREFIX + adaptationStatus.getOutcome().toUpperCase()
        ));
    }

    private ModelStreamEvent appendFailure(
            StreamingRequestContext context,
            ErrorCode errorCode,
            String content
    ) {
        return appendEvent(context, ModelStreamEvent.EVENT_FAILURE, content, ModelType.GENERIC, errorCode.getCode());
    }

    private ModelStreamEvent appendEvent(
            StreamingRequestContext context,
            String eventName,
            String content,
            ModelType modelType,
            String statusCode
    ) {
        return sessionStore.append(context, eventName, content, modelType, statusCode);
    }

    private void sendEvents(SseEmitter emitter, List<ModelStreamEvent> events) {
        try {
            for (ModelStreamEvent event : events) {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.getEventId()))
                        .name(event.getEventName())
                        .data(event));
            }
            emitter.complete();
        } catch (IOException exception) {
            LOGGER.warn("SSE client disconnected before stream completion");
            emitter.completeWithError(exception);
        }
    }

    private static String fallbackStartStatus(ModelStreamClient.ModelStreamException exception) {
        return exception.getStatusCode();
    }

    private static String trimDuplicatePrefix(String candidate, String emittedContent) {
        if (candidate.startsWith(emittedContent)) {
            return candidate.substring(emittedContent.length()).stripLeading();
        }
        if (emittedContent.endsWith(candidate)) {
            return "";
        }
        return candidate;
    }

    private static StreamingModelRequest requireRequest(StreamingModelRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "request body is required");
        }
        return request;
    }
}
