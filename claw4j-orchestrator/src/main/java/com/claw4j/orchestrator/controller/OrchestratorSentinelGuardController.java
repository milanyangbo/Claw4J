package com.claw4j.orchestrator.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.orchestrator.dto.AgentCallGuardStatus;
import com.claw4j.orchestrator.service.AgentCallGuardService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Orchestrator Sentinel proof endpoints.
 */
@RestController
@RequestMapping("/internal/orchestrator/sentinel")
public class OrchestratorSentinelGuardController {

    private final AgentCallGuardService agentCallGuardService;

    /**
     * Creates the Orchestrator Sentinel guard controller.
     *
     * @param agentCallGuardService service that applies Sentinel Agent-call guard logic
     */
    public OrchestratorSentinelGuardController(AgentCallGuardService agentCallGuardService) {
        this.agentCallGuardService = Objects.requireNonNull(
                agentCallGuardService,
                "agentCallGuardService must not be null"
        );
    }

    /**
     * Calls the Sentinel-protected Agent-call proof path.
     *
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @param simulateFailure whether to simulate an Agent-call failure
     * @return shared response containing the Agent-call guard status
     */
    @GetMapping("/agent-call")
    public ApiResponse<AgentCallGuardStatus> getAgentCallStatus(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestParam(value = "fail", defaultValue = "false") boolean simulateFailure
    ) {
        return agentCallGuardService.guardedAgentCall(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                simulateFailure
        );
    }
}
