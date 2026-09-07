package com.claw4j.gateway.service;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.client.OrchestratorClient;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Validates Gateway context and delegates internal calls to Orchestrator.
 */
@Service
public class OrchestratorGatewayService {

    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String EMPTY_DOWNSTREAM_RESPONSE_MESSAGE = "Orchestrator returned an empty response";

    private final OrchestratorClient orchestratorClient;

    /**
     * Creates the Gateway service for Orchestrator calls.
     *
     * @param orchestratorClient OpenFeign client for Orchestrator
     */
    public OrchestratorGatewayService(OrchestratorClient orchestratorClient) {
        this.orchestratorClient = Objects.requireNonNull(orchestratorClient, "orchestratorClient must not be null");
    }

    /**
     * Returns Orchestrator status after validating required internal call context.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing Orchestrator service identity
     */
    public ApiResponse<InternalServiceStatus> getOrchestratorStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        ApiResponse<InternalServiceStatus> response = orchestratorClient.getStatus(
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
