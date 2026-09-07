package com.claw4j.orchestrator.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.orchestrator.service.A2aBrokerGatewayService;
import com.claw4j.orchestrator.service.OrchestratorStatusService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Orchestrator internal endpoints for service-to-service proof calls.
 */
@RestController
@RequestMapping("/internal/orchestrator")
public class OrchestratorInternalCallController {

    private final OrchestratorStatusService statusService;
    private final A2aBrokerGatewayService a2aBrokerGatewayService;

    /**
     * Creates the Orchestrator internal call controller.
     *
     * @param statusService service that builds validated status responses
     * @param a2aBrokerGatewayService service that performs validated A2A Broker calls
     */
    public OrchestratorInternalCallController(
            OrchestratorStatusService statusService,
            A2aBrokerGatewayService a2aBrokerGatewayService
    ) {
        this.statusService = Objects.requireNonNull(statusService, "statusService must not be null");
        this.a2aBrokerGatewayService = Objects.requireNonNull(
                a2aBrokerGatewayService,
                "a2aBrokerGatewayService must not be null"
        );
    }

    /**
     * Returns Orchestrator internal service identity.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing Orchestrator service identity
     */
    @GetMapping("/status")
    public ApiResponse<InternalServiceStatus> getStatus(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        return statusService.getStatus(requestId, tenantId, userId, idempotencyKey);
    }

    /**
     * Calls A2A Broker through the governed internal Feign path.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing A2A Broker service identity
     */
    @GetMapping("/a2a/status")
    public ApiResponse<InternalServiceStatus> getA2aBrokerStatus(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        return a2aBrokerGatewayService.getA2aBrokerStatus(requestId, tenantId, userId, idempotencyKey);
    }
}
