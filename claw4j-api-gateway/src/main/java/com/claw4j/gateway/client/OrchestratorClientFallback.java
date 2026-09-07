package com.claw4j.gateway.client;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Fail-closed fallback for unavailable Orchestrator calls.
 */
@Component
public class OrchestratorClientFallback implements OrchestratorClient {

    private static final String UNAVAILABLE_MESSAGE = "Orchestrator service is unavailable";

    /**
     * Raises a stable downstream-unavailable outcome for Orchestrator calls.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return never returns because degradation is represented by a business exception
     */
    @Override
    public ApiResponse<InternalServiceStatus> getStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
