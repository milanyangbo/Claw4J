package com.claw4j.gateway.client;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.dto.StreamingModelRequest;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign client for Gateway calls to Orchestrator.
 */
@FeignClient(name = OrchestratorClient.SERVICE_NAME, contextId = "orchestratorClient", fallback = OrchestratorClientFallback.class)
public interface OrchestratorClient {

    String SERVICE_NAME = "claw4j-orchestrator";

    /**
     * Returns Orchestrator internal service identity.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing Orchestrator service identity
     */
    @GetMapping("/internal/orchestrator/status")
    ApiResponse<InternalServiceStatus> getStatus(
            @RequestHeader(CommonConstants.REQUEST_ID_HEADER) String requestId,
            @RequestHeader(CommonConstants.TENANT_ID_HEADER) String tenantId,
            @RequestHeader(CommonConstants.USER_ID_HEADER) String userId,
            @RequestHeader(CommonConstants.IDEMPOTENCY_KEY_HEADER) String idempotencyKey
    );

    /**
     * Opens Orchestrator's internal streaming model endpoint.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @param sessionId stream session identifier
     * @param lastEventId optional SSE event position for reconnect
     * @param request streaming model business payload
     * @return raw OpenFeign response carrying the downstream SSE stream
     */
    @PostMapping(
            value = "/internal/orchestrator/model/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    Response streamModel(
            @RequestHeader(CommonConstants.REQUEST_ID_HEADER) String requestId,
            @RequestHeader(CommonConstants.TENANT_ID_HEADER) String tenantId,
            @RequestHeader(CommonConstants.USER_ID_HEADER) String userId,
            @RequestHeader(CommonConstants.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestHeader(CommonConstants.STREAM_SESSION_ID_HEADER) String sessionId,
            @RequestHeader(value = CommonConstants.LAST_EVENT_ID_HEADER, required = false) String lastEventId,
            @RequestBody StreamingModelRequest request
    );
}
