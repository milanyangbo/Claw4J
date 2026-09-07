package com.claw4j.a2a.service;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds defensive A2A Broker identity responses for internal service calls.
 */
@Service
public class A2aBrokerStatusService {

    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";

    private final String serviceName;
    private final String role;
    private final int instancePort;

    /**
     * Creates the A2A Broker status service from configured service metadata.
     *
     * @param serviceName registered service name
     * @param role service role metadata
     * @param instancePort configured service port
     */
    public A2aBrokerStatusService(
            @Value("${spring.application.name}") String serviceName,
            @Value("${spring.cloud.nacos.discovery.metadata.role}") String role,
            @Value("${server.port}") int instancePort
    ) {
        this.serviceName = serviceName;
        this.role = role;
        this.instancePort = instancePort;
    }

    /**
     * Returns A2A Broker identity after validating required internal call context.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing A2A Broker service identity
     */
    public ApiResponse<InternalServiceStatus> getStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        return ApiResponse.success(InternalServiceStatus.of(serviceName, role, instancePort, requestId));
    }

    private static void requireContext(String value, String contextName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, contextName + MISSING_CONTEXT_MESSAGE_SUFFIX);
        }
    }
}
