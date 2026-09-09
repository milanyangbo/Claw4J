package com.claw4j.orchestrator.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.orchestrator.dto.StreamingRequestContext;
import com.claw4j.orchestrator.service.StreamingModelService;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes Orchestrator streaming model endpoints.
 */
@RestController
@RequestMapping("/internal/orchestrator/model")
public class OrchestratorStreamingModelController {

    private final StreamingModelService streamingModelService;

    /**
     * Creates the streaming model controller.
     *
     * @param streamingModelService service that owns streaming model governance
     */
    public OrchestratorStreamingModelController(StreamingModelService streamingModelService) {
        this.streamingModelService = Objects.requireNonNull(
                streamingModelService,
                "streamingModelService must not be null"
        );
    }

    /**
     * Opens the streaming model endpoint.
     *
     * @param requestId request id Header
     * @param tenantId tenant id Header
     * @param userId user id Header
     * @param idempotencyKey idempotency key Header
     * @param sessionId stream session id Header
     * @param lastEventId optional SSE last event id Header
     * @param request business streaming request payload
     * @return SSE emitter for streaming model events
     */
    @PostMapping(
            value = "/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CommonConstants.STREAM_SESSION_ID_HEADER, required = false) String sessionId,
            @RequestHeader(value = CommonConstants.LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestBody StreamingModelRequest request
    ) {
        StreamingRequestContext context = StreamingRequestContext.fromHeaders(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                lastEventId
        );
        return streamingModelService.stream(request, context);
    }
}
