package com.claw4j.gateway.client;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import feign.Response;
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

    /**
     * Raises a stable downstream-unavailable outcome for Orchestrator stream calls.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @param sessionId stream session identifier
     * @param lastEventId optional SSE event position for reconnect
     * @param request streaming model business payload
     * @return never returns because degradation is represented by a business exception
     */
    @Override
    public Response streamModel(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            String lastEventId,
            StreamingModelRequest request
    ) {
        throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
