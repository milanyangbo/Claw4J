package com.claw4j.gateway.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.gateway.service.OrchestratorGatewayService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Exposes Gateway streaming endpoints backed by Orchestrator model governance.
 */
@RestController
public class GatewayStreamingModelController {

    private final OrchestratorGatewayService orchestratorGatewayService;

    /**
     * Creates the Gateway streaming model controller.
     *
     * @param orchestratorGatewayService service that forwards governed calls to Orchestrator
     */
    public GatewayStreamingModelController(OrchestratorGatewayService orchestratorGatewayService) {
        this.orchestratorGatewayService = Objects.requireNonNull(
                orchestratorGatewayService,
                "orchestratorGatewayService must not be null"
        );
    }

    /**
     * Opens the external Gateway SSE endpoint and forwards the request to Orchestrator.
     *
     * @param requestId request id Header
     * @param tenantId tenant id Header
     * @param userId user id Header
     * @param idempotencyKey idempotency key Header
     * @param sessionId stream session id Header
     * @param lastEventId optional SSE last event id Header
     * @param request business streaming request payload
     * @return SSE response body proxied from Orchestrator
     */
    @PostMapping(
            value = "/api/model/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CommonConstants.STREAM_SESSION_ID_HEADER, required = false) String sessionId,
            @RequestHeader(value = CommonConstants.LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestBody StreamingModelRequest request
    ) {
        return orchestratorGatewayService.streamModel(
                request,
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                lastEventId
        );
    }

    /**
     * Opens a browser-friendly Gateway SSE endpoint and creates local context for the internal stream.
     *
     * @param query user query from the browser address bar
     * @return SSE response body proxied from Orchestrator
     */
    @GetMapping(
            value = "/ai/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> chat(@RequestParam(value = "query", required = false) String query) {
        return orchestratorGatewayService.streamBrowserModel(query);
    }
}
