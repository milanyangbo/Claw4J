package com.claw4j.gateway.client;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
}
