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
import java.util.function.Function;
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
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ADAPTED_PREFIX = "CONTEXT_";
    private static final String FALLBACK_STARTED_CONTENT = "fallback continuation started";
    private static final String STREAM_COMPLETED_CONTENT = "stream completed";
    private static final String RESUME_DISABLED_CONTENT = "streaming resume is disabled";
    private static final String CONTEXT_TOO_LARGE_CONTENT = "context exceeds target model budget";
    private static final String PARSER_FAILURE_CONTENT = "model output could not be parsed";
    private static final String RESUME_EXPIRED_CONTENT = "stream resume state is unavailable";
    private static final String FALLBACK_FAILURE_CONTENT = "fallback model provider failed";

    private final OrchestratorStreamingModelProperties properties;
    private final ModelStreamClient modelStreamClient;
    private final ModelContextAdapter contextAdapter;
    private final ModelOutputParser outputParser;
    private final StreamingResumePromptBuilder resumePromptBuilder;
    private final StreamingSessionStore sessionStore;
    private final Function<Long, SseEmitter> emitterFactory;

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
        this(
                properties,
                modelStreamClient,
                contextAdapter,
                outputParser,
                resumePromptBuilder,
                sessionStore,
                SseEmitter::new
        );
    }

    StreamingModelService(
            OrchestratorStreamingModelProperties properties,
            ModelStreamClient modelStreamClient,
            ModelContextAdapter contextAdapter,
            ModelOutputParser outputParser,
            StreamingResumePromptBuilder resumePromptBuilder,
            StreamingSessionStore sessionStore,
            Function<Long, SseEmitter> emitterFactory
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.modelStreamClient = Objects.requireNonNull(modelStreamClient, "modelStreamClient must not be null");
        this.contextAdapter = Objects.requireNonNull(contextAdapter, "contextAdapter must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
        this.resumePromptBuilder = Objects.requireNonNull(resumePromptBuilder, "resumePromptBuilder must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory must not be null");
    }

    /**
     * Opens an MVC SSE stream for model output.
     *
     * @param request business streaming request
     * @param context Header-derived request context
     * @return SSE emitter that publishes stream events as model tokens arrive
     */
    public SseEmitter stream(StreamingModelRequest request, StreamingRequestContext context) {
        StreamingModelRequest validatedRequest = requireRequest(request);
        Objects.requireNonNull(context, "context must not be null");
        SseEmitter emitter = emitterFactory.apply(properties.getResponseTimeout().toMillis());
        CompletableFuture.runAsync(() -> streamToEmitter(validatedRequest, context, emitter));
        return emitter;
    }

    /**
     * Executes the streaming path synchronously for contract tests.
     *
     * @param request business streaming request
     * @param context Header-derived request context
     * @return ordered stream events
     */
    public List<ModelStreamEvent> streamEvents(StreamingModelRequest request, StreamingRequestContext context) {
        StreamingModelRequest validatedRequest = requireRequest(request);
        Objects.requireNonNull(context, "context must not be null");
        List<ModelStreamEvent> events = new ArrayList<>();
        streamToSink(validatedRequest, context, events::add);
        return events;
    }

    private void streamToEmitter(
            StreamingModelRequest request,
            StreamingRequestContext context,
            SseEmitter emitter
    ) {
        try {
            streamToSink(request, context, event -> sendEvent(emitter, event));
            emitter.complete();
        } catch (EventDeliveryException exception) {
            LOGGER.warn("SSE client disconnected before stream completion");
            emitter.completeWithError(exception.getCause());
        } catch (RuntimeException exception) {
            LOGGER.warn("SSE stream failed with sanitized error type={}", exception.getClass().getName());
            emitter.completeWithError(exception);
        }
    }

    private void streamToSink(
            StreamingModelRequest request,
            StreamingRequestContext context,
            EventSink eventSink
    ) {
        Optional<Long> lastEventId = context.getLastEventId();
        if (lastEventId.isPresent()) {
            emitReplayEvents(context, lastEventId.get(), eventSink);
            return;
        }

        sessionStore.start(context);
        ModelType primaryModelType = properties.getRouting().getPrimaryModelType();
        ContextAdaptationStatus primaryContext = contextAdapter.adapt(request.getQuery(), primaryModelType);
        if (primaryContext.isRejected()) {
            emitEvent(eventSink, appendFailure(context, ErrorCode.MODEL_CONTEXT_TOO_LARGE, CONTEXT_TOO_LARGE_CONTENT));
            return;
        }
        recordAdaptationIfNeeded(context, eventSink, primaryContext);

        StringBuilder emittedContent = new StringBuilder();
        AtomicBoolean emittedAnyContent = new AtomicBoolean(false);
        try {
            modelStreamClient.stream(
                    primaryModelType,
                    primaryContext.getContent(),
                    request,
                    context,
                    token -> emitVisibleToken(context, eventSink, emittedContent, emittedAnyContent, primaryModelType, token)
            );
            if (!emittedAnyContent.get()) {
                emitEvent(eventSink, appendFailure(context, ErrorCode.MODEL_OUTPUT_PARSER_FAILURE, PARSER_FAILURE_CONTENT));
                return;
            }
            emitEvent(eventSink, appendEvent(
                    context,
                    ModelStreamEvent.EVENT_COMPLETE,
                    STREAM_COMPLETED_CONTENT,
                    primaryModelType,
                    STATUS_COMPLETED
            ));
        } catch (ModelStreamClient.ModelStreamException exception) {
            continueWithFallback(request, context, eventSink, emittedContent, exception, emittedAnyContent);
        } catch (BusinessException exception) {
            emitEvent(eventSink, appendFailure(context, exception.getErrorCode(), exception.getErrorCode().getMessage()));
        }
    }

    private void emitReplayEvents(StreamingRequestContext context, long lastEventId, EventSink eventSink) {
        for (ModelStreamEvent event : replayEvents(context, lastEventId)) {
            emitEvent(eventSink, event);
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

    private void continueWithFallback(
            StreamingModelRequest request,
            StreamingRequestContext context,
            EventSink eventSink,
            StringBuilder emittedContent,
            ModelStreamClient.ModelStreamException exception,
            AtomicBoolean emittedAnyContent
    ) {
        ModelType primaryModelType = properties.getRouting().getPrimaryModelType();
        if (!properties.getFallback().isResumeEnabled()) {
            emitEvent(eventSink, appendEvent(
                    context,
                    ModelStreamEvent.EVENT_FAILURE,
                    RESUME_DISABLED_CONTENT,
                    primaryModelType,
                    STATUS_RESUME_DISABLED
            ));
            return;
        }
        ModelType fallbackModelType = properties.getRouting().getFallbackModelType();
        emitEvent(eventSink, appendEvent(
                context,
                ModelStreamEvent.EVENT_FALLBACK_START,
                FALLBACK_STARTED_CONTENT,
                fallbackModelType,
                fallbackStartStatus(exception)
        ));
        String resumePrompt = buildFallbackPrompt(request, context, emittedContent);
        ContextAdaptationStatus fallbackContext = contextAdapter.adapt(resumePrompt, fallbackModelType);
        if (fallbackContext.isRejected()) {
            emitEvent(eventSink, appendFailure(context, ErrorCode.MODEL_CONTEXT_TOO_LARGE, CONTEXT_TOO_LARGE_CONTENT));
            return;
        }
        recordAdaptationIfNeeded(context, eventSink, fallbackContext);
        try {
            modelStreamClient.stream(
                    fallbackModelType,
                    fallbackContext.getContent(),
                    request,
                    context,
                    token -> emitFallbackToken(context, eventSink, emittedContent, emittedAnyContent, fallbackModelType, token)
            );
        } catch (EventDeliveryException exceptionDuringDelivery) {
            throw exceptionDuringDelivery;
        } catch (BusinessException businessException) {
            emitEvent(eventSink, appendFailure(
                    context,
                    businessException.getErrorCode(),
                    businessException.getErrorCode().getMessage()
            ));
            return;
        } catch (RuntimeException runtimeException) {
            emitEvent(eventSink, appendFailure(
                    context,
                    ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE,
                    FALLBACK_FAILURE_CONTENT
            ));
            return;
        }
        if (!emittedAnyContent.get()) {
            emitEvent(eventSink, appendFailure(context, ErrorCode.MODEL_OUTPUT_PARSER_FAILURE, PARSER_FAILURE_CONTENT));
            return;
        }
        emitEvent(eventSink, appendEvent(
                context,
                ModelStreamEvent.EVENT_COMPLETE,
                STREAM_COMPLETED_CONTENT,
                fallbackModelType,
                STATUS_COMPLETED
        ));
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
            EventSink eventSink,
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
        emitEvent(eventSink, appendEvent(context, ModelStreamEvent.EVENT_TOKEN, visibleContent, modelType, STATUS_OK));
        emittedContent.append(visibleContent);
        emittedAnyContent.set(true);
    }

    private void emitFallbackToken(
            StreamingRequestContext context,
            EventSink eventSink,
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
        emitEvent(eventSink, appendEvent(context, ModelStreamEvent.EVENT_TOKEN, visibleContent, modelType, STATUS_OK));
        emittedContent.append(visibleContent);
        emittedAnyContent.set(true);
    }

    private void recordAdaptationIfNeeded(
            StreamingRequestContext context,
            EventSink eventSink,
            ContextAdaptationStatus adaptationStatus
    ) {
        if (!adaptationStatus.isAdapted()) {
            return;
        }
        emitEvent(eventSink, appendEvent(
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

    private static void sendEvent(SseEmitter emitter, ModelStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getEventId()))
                .name(event.getEventName())
                .data(event));
    }

    private static void emitEvent(EventSink eventSink, ModelStreamEvent event) {
        try {
            eventSink.accept(event);
        } catch (IOException exception) {
            throw new EventDeliveryException(exception);
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

    @FunctionalInterface
    private interface EventSink {

        void accept(ModelStreamEvent event) throws IOException;
    }

    private static final class EventDeliveryException extends RuntimeException {

        private EventDeliveryException(IOException cause) {
            super(cause);
        }
    }
}
