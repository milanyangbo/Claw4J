package com.claw4j.gateway.controller;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.gateway.dto.DynamicConfigStatus;
import com.claw4j.gateway.service.GatewayDynamicConfigService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Gateway dynamic configuration proof endpoints.
 */
@RestController
@RequestMapping("/internal/gateway/config")
public class GatewayDynamicConfigController {

    private final GatewayDynamicConfigService gatewayDynamicConfigService;

    /**
     * Creates the Gateway dynamic configuration controller.
     *
     * @param gatewayDynamicConfigService service that resolves dynamic configuration state
     */
    public GatewayDynamicConfigController(GatewayDynamicConfigService gatewayDynamicConfigService) {
        this.gatewayDynamicConfigService = Objects.requireNonNull(
                gatewayDynamicConfigService,
                "gatewayDynamicConfigService must not be null"
        );
    }

    /**
     * Returns the current Gateway dynamic configuration status.
     *
     * @return shared response containing dynamic configuration status
     */
    @GetMapping("/dynamic")
    public ApiResponse<DynamicConfigStatus> getDynamicConfigStatus() {
        return ApiResponse.success(gatewayDynamicConfigService.currentStatus());
    }
}
