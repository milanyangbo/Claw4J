package com.claw4j.orchestrator.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.config.OrchestratorSentinelProperties;
import com.claw4j.orchestrator.dto.AgentCallGuardStatus;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Applies Sentinel circuit breaking to an Orchestrator Agent-call proof boundary.
 */
@Service
public class AgentCallGuardService {

    private static final int SENTINEL_ENTRY_COUNT = 1;
    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String SIMULATED_FAILURE_MESSAGE = "Simulated Agent call failure";

    private final OrchestratorSentinelProperties properties;
    private final AtomicInteger protectedExecutionCount = new AtomicInteger();

    /**
     * Creates the Agent-call guard service.
     *
     * @param properties Orchestrator Sentinel properties
     */
    public AgentCallGuardService(OrchestratorSentinelProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Loads local default Sentinel degrade rules for the Agent-call proof boundary.
     */
    @PostConstruct
    public void loadLocalRules() {
        DegradeRule degradeRule = new DegradeRule(properties.getAgentCallResourceName());
        degradeRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        degradeRule.setCount(properties.getErrorRatioThreshold());
        degradeRule.setMinRequestAmount(properties.getMinimumRequestAmount());
        degradeRule.setStatIntervalMs(properties.getStatIntervalMs());
        degradeRule.setTimeWindow(properties.getRecoveryWindowSeconds());
        DegradeRuleManager.loadRules(List.of(degradeRule));
    }

    /**
     * Returns an Agent-call proof response after Sentinel admits the protected request.
     *
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @param simulateFailure whether to simulate an Agent-call failure
     * @return shared response containing the Agent-call guard status
     */
    public ApiResponse<AgentCallGuardStatus> guardedAgentCall(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            boolean simulateFailure
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        Entry entry = null;
        try {
            entry = SphU.entry(properties.getAgentCallResourceName(), EntryType.IN, SENTINEL_ENTRY_COUNT);
            protectedExecutionCount.incrementAndGet();
            if (simulateFailure) {
                throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, SIMULATED_FAILURE_MESSAGE);
            }
            return ApiResponse.success(AgentCallGuardStatus.success(
                    properties.getAgentCallResourceName(),
                    requestId,
                    tenantId
            ));
        } catch (BlockException exception) {
            return ApiResponse.success(AgentCallGuardStatus.fallback(
                    properties.getAgentCallResourceName(),
                    requestId,
                    tenantId
            ));
        } catch (RuntimeException exception) {
            if (entry != null) {
                Tracer.traceEntry(exception, entry);
            }
            throw exception;
        } finally {
            if (entry != null) {
                entry.exit(SENTINEL_ENTRY_COUNT);
            }
        }
    }

    /**
     * Returns how many calls reached the protected Agent-call logic.
     *
     * @return protected execution count
     */
    public int getProtectedExecutionCount() {
        return protectedExecutionCount.get();
    }

    private static void requireContext(String value, String contextName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, contextName + MISSING_CONTEXT_MESSAGE_SUFFIX);
        }
    }
}
