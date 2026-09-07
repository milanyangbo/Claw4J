package com.claw4j.orchestrator.service;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.client.A2aBrokerClient;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Validates Orchestrator context and delegates internal calls to A2A Broker.
 */
@Service
public class A2aBrokerGatewayService {

    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String EMPTY_DOWNSTREAM_RESPONSE_MESSAGE = "A2A Broker returned an empty response";

    private final A2aBrokerClient a2aBrokerClient;

    /**
     * Creates the Orchestrator service for A2A Broker calls.
     *
     * @param a2aBrokerClient OpenFeign client for A2A Broker
     */
    public A2aBrokerGatewayService(A2aBrokerClient a2aBrokerClient) {
        this.a2aBrokerClient = Objects.requireNonNull(a2aBrokerClient, "a2aBrokerClient must not be null");
    }

    /**
     * Returns A2A Broker status after validating required internal call context.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing A2A Broker service identity
     */
    public ApiResponse<InternalServiceStatus> getA2aBrokerStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        ApiResponse<InternalServiceStatus> response = a2aBrokerClient.getStatus(
                requestId,
                tenantId,
                userId,
                idempotencyKey
        );
        if (response == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, EMPTY_DOWNSTREAM_RESPONSE_MESSAGE);
        }
        return response;
    }

    private static void requireContext(String value, String contextName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, contextName + MISSING_CONTEXT_MESSAGE_SUFFIX);
        }
    }
}
