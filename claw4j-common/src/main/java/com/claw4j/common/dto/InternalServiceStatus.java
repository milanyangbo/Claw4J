package com.claw4j.common.dto;

import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Internal service identity returned by Claw4J service-to-service proof calls.
 */
public final class InternalServiceStatus {

    private static final int MINIMUM_VALID_PORT = 1;
    private static final String SERVICE_NAME_FIELD = "serviceName";
    private static final String ROLE_FIELD = "role";
    private static final String REQUEST_ID_FIELD = "requestId";
    private static final String INSTANCE_PORT_FIELD = "instancePort";
    private static final String BLANK_FIELD_MESSAGE_SUFFIX = " must not be blank";
    private static final String INVALID_PORT_MESSAGE = "instancePort must be positive";

    private final String serviceName;
    private final String role;
    private final int instancePort;
    private final String requestId;

    /**
     * Creates an internal service status DTO.
     *
     * @param serviceName registered service name
     * @param role service role metadata
     * @param instancePort responding service instance port
     * @param requestId request identifier propagated through the call chain
     */
    @JsonCreator
    public InternalServiceStatus(
            @JsonProperty(SERVICE_NAME_FIELD) String serviceName,
            @JsonProperty(ROLE_FIELD) String role,
            @JsonProperty(INSTANCE_PORT_FIELD) int instancePort,
            @JsonProperty(REQUEST_ID_FIELD) String requestId
    ) {
        this.serviceName = requireText(serviceName, SERVICE_NAME_FIELD);
        this.role = requireText(role, ROLE_FIELD);
        this.instancePort = requirePort(instancePort);
        this.requestId = requireText(requestId, REQUEST_ID_FIELD);
    }

    /**
     * Creates an internal service status DTO.
     *
     * @param serviceName registered service name
     * @param role service role metadata
     * @param instancePort responding service instance port
     * @param requestId request identifier propagated through the call chain
     * @return validated internal service status
     */
    public static InternalServiceStatus of(String serviceName, String role, int instancePort, String requestId) {
        return new InternalServiceStatus(serviceName, role, instancePort, requestId);
    }

    /**
     * Returns the registered service name.
     *
     * @return registered service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Returns the service role metadata.
     *
     * @return service role
     */
    public String getRole() {
        return role;
    }

    /**
     * Returns the responding service instance port.
     *
     * @return service instance port
     */
    public int getInstancePort() {
        return instancePort;
    }

    /**
     * Returns the propagated request identifier.
     *
     * @return request identifier
     */
    public String getRequestId() {
        return requestId;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, fieldName + BLANK_FIELD_MESSAGE_SUFFIX);
        }
        return value;
    }

    private static int requirePort(int value) {
        if (value < MINIMUM_VALID_PORT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, INVALID_PORT_MESSAGE);
        }
        return value;
    }
}
