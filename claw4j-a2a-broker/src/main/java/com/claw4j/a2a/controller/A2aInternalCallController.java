package com.claw4j.a2a.controller;

import com.claw4j.a2a.service.A2aBrokerStatusService;
import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes A2A Broker internal endpoints for service-to-service proof calls.
 */
@RestController
@RequestMapping("/internal/a2a")
public class A2aInternalCallController {

    private final A2aBrokerStatusService statusService;

    /**
     * Creates the A2A Broker internal call controller.
     *
     * @param statusService service that builds validated status responses
     */
    public A2aInternalCallController(A2aBrokerStatusService statusService) {
        this.statusService = Objects.requireNonNull(statusService, "statusService must not be null");
    }

    /**
     * Returns A2A Broker internal service identity.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing A2A Broker service identity
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
}
