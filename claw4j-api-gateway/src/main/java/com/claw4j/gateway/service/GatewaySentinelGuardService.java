package com.claw4j.gateway.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.config.GatewaySentinelProperties;
import com.claw4j.gateway.dto.SentinelGuardStatus;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Applies Sentinel flow control to a Gateway proof boundary after context validation.
 */
@Service
public class GatewaySentinelGuardService {

    private static final int SENTINEL_ENTRY_COUNT = 1;
    private static final int TENANT_PARAM_INDEX = 0;
    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String RATE_LIMIT_MESSAGE = "Gateway Sentinel rate limit triggered";

    private final GatewaySentinelProperties properties;

    /**
     * Creates the Gateway Sentinel guard service.
     *
     * @param properties Gateway Sentinel properties
     */
    public GatewaySentinelGuardService(GatewaySentinelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Loads local default Sentinel flow rules for the Gateway proof boundary.
     */
    @PostConstruct
    public void loadLocalRules() {
        FlowRule flowRule = new FlowRule(properties.getResourceName());
        flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flowRule.setCount(properties.getGlobalQpsThreshold());

        ParamFlowRule tenantRule = new ParamFlowRule(properties.getResourceName());
        tenantRule.setParamIdx(TENANT_PARAM_INDEX);
        tenantRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        tenantRule.setCount(properties.getTenantQpsThreshold());

        FlowRuleManager.loadRules(List.of(flowRule));
        ParamFlowRuleManager.loadRules(List.of(tenantRule));
    }

    /**
     * Returns a status response after Sentinel admits the protected request.
     *
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @return shared response containing the guard status
     */
    public ApiResponse<SentinelGuardStatus> guardedStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        Entry entry = null;
        try {
            entry = SphU.entry(properties.getResourceName(), EntryType.IN, SENTINEL_ENTRY_COUNT, tenantId);
            SentinelGuardStatus status = SentinelGuardStatus.allowed(
                    properties.getResourceName(),
                    tenantId,
                    requestId,
                    userId,
                    idempotencyKey
            );
            return ApiResponse.success(status);
        } catch (BlockException exception) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, RATE_LIMIT_MESSAGE);
        } finally {
            if (entry != null) {
                entry.exit(SENTINEL_ENTRY_COUNT, tenantId);
            }
        }
    }

    private static void requireContext(String value, String contextName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, contextName + MISSING_CONTEXT_MESSAGE_SUFFIX);
        }
    }
}
