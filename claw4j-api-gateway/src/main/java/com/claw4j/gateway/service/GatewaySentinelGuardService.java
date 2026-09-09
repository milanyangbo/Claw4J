package com.claw4j.gateway.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Applies Sentinel flow control to Gateway model streaming ingress after context validation.
 */
@Service
public class GatewaySentinelGuardService {

    public static final String MODEL_STREAM_RESOURCE_NAME = "claw4j-gateway-ingress";
    private static final int SENTINEL_ENTRY_COUNT = 1;
    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String RATE_LIMIT_MESSAGE = "Gateway Sentinel rate limit triggered";

    /**
     * Executes a model streaming operation after Sentinel admits the protected request.
     *
     * @param requestId request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key
     * @param operation protected stream operation
     * @param <T> operation return type
     * @return protected operation result
     */
    public <T> T guardModelStream(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            Supplier<T> operation
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operation, "operation must not be null");
        Entry entry = null;
        try {
            entry = SphU.entry(MODEL_STREAM_RESOURCE_NAME, EntryType.IN, SENTINEL_ENTRY_COUNT, tenantId);
            return operation.get();
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
