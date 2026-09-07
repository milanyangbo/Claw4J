package com.claw4j.orchestrator.client;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign client for Orchestrator calls to A2A Broker.
 */
@FeignClient(name = A2aBrokerClient.SERVICE_NAME, contextId = "a2aBrokerClient", fallback = A2aBrokerClientFallback.class)
public interface A2aBrokerClient {

    String SERVICE_NAME = "claw4j-a2a-broker";

    /**
     * Returns A2A Broker internal service identity.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing A2A Broker service identity
     */
    @GetMapping("/internal/a2a/status")
    ApiResponse<InternalServiceStatus> getStatus(
            @RequestHeader(CommonConstants.REQUEST_ID_HEADER) String requestId,
            @RequestHeader(CommonConstants.TENANT_ID_HEADER) String tenantId,
            @RequestHeader(CommonConstants.USER_ID_HEADER) String userId,
            @RequestHeader(CommonConstants.IDEMPOTENCY_KEY_HEADER) String idempotencyKey
    );
}
