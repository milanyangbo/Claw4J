package com.claw4j.gateway.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.gateway.service.OrchestratorGatewayService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Gateway internal endpoints for service-to-service calls.
 */
@RestController
@RequestMapping("/internal/gateway")
public class GatewayInternalCallController {

    private final OrchestratorGatewayService orchestratorGatewayService;

    /**
     * Creates the Gateway internal call controller.
     *
     * @param orchestratorGatewayService service that performs validated Orchestrator calls
     */
    public GatewayInternalCallController(OrchestratorGatewayService orchestratorGatewayService) {
        this.orchestratorGatewayService = Objects.requireNonNull(
                orchestratorGatewayService,
                "orchestratorGatewayService must not be null"
        );
    }

    /**
     * Calls Orchestrator through the governed internal Feign path.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing Orchestrator service identity
     */
    @GetMapping("/orchestrator/status")
    public ApiResponse<InternalServiceStatus> getOrchestratorStatus(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        return orchestratorGatewayService.getOrchestratorStatus(requestId, tenantId, userId, idempotencyKey);
    }
}
