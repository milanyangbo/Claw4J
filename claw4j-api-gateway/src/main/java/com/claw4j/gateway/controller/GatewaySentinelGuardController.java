package com.claw4j.gateway.controller;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.gateway.dto.SentinelGuardStatus;
import com.claw4j.gateway.service.GatewaySentinelGuardService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Gateway Sentinel proof endpoints.
 */
@RestController
@RequestMapping("/internal/gateway/sentinel")
public class GatewaySentinelGuardController {

    private final GatewaySentinelGuardService gatewaySentinelGuardService;

    /**
     * Creates the Gateway Sentinel guard controller.
     *
     * @param gatewaySentinelGuardService service that applies Sentinel guard logic
     */
    public GatewaySentinelGuardController(GatewaySentinelGuardService gatewaySentinelGuardService) {
        this.gatewaySentinelGuardService = Objects.requireNonNull(
                gatewaySentinelGuardService,
                "gatewaySentinelGuardService must not be null"
        );
    }

    /**
     * Calls the Sentinel-protected Gateway proof path.
     *
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @return shared response containing the guard status
     */
    @GetMapping("/guarded")
    public ApiResponse<SentinelGuardStatus> getGuardedStatus(
            @RequestHeader(value = CommonConstants.REQUEST_ID_HEADER, required = false) String requestId,
            @RequestHeader(value = CommonConstants.TENANT_ID_HEADER, required = false) String tenantId,
            @RequestHeader(value = CommonConstants.USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = CommonConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey
    ) {
        return gatewaySentinelGuardService.guardedStatus(requestId, tenantId, userId, idempotencyKey);
    }
}
