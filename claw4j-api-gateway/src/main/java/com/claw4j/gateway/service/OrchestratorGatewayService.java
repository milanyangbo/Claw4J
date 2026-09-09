package com.claw4j.gateway.service;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.common.util.IdUtil;
import com.claw4j.gateway.client.OrchestratorClient;
import feign.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Validates Gateway context and delegates internal calls to Orchestrator.
 */
@Service
public class OrchestratorGatewayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorGatewayService.class);
    private static final String MISSING_CONTEXT_MESSAGE_SUFFIX = " header is required";
    private static final String EMPTY_DOWNSTREAM_RESPONSE_MESSAGE = "Orchestrator returned an empty response";
    private static final String EMPTY_STREAM_REQUEST_MESSAGE = "streaming request body is required";
    private static final String EMPTY_DOWNSTREAM_STREAM_MESSAGE = "Orchestrator returned an empty stream";
    private static final String UNAVAILABLE_STREAM_MESSAGE = "Orchestrator stream is unavailable";
    private static final String STREAM_COPY_FAILURE_MESSAGE = "Orchestrator stream forwarding failed";
    private static final int MINIMUM_SUCCESS_STATUS = 200;
    private static final int MAXIMUM_SUCCESS_STATUS_EXCLUSIVE = 300;

    private final OrchestratorClient orchestratorClient;
    private final GatewaySentinelGuardService gatewaySentinelGuardService;

    /**
     * Creates the Gateway service for Orchestrator calls.
     *
     * @param orchestratorClient OpenFeign client for Orchestrator
     * @param gatewaySentinelGuardService Gateway Sentinel guard for model stream ingress
     */
    public OrchestratorGatewayService(
            OrchestratorClient orchestratorClient,
            GatewaySentinelGuardService gatewaySentinelGuardService
    ) {
        this.orchestratorClient = Objects.requireNonNull(orchestratorClient, "orchestratorClient must not be null");
        this.gatewaySentinelGuardService = Objects.requireNonNull(
                gatewaySentinelGuardService,
                "gatewaySentinelGuardService must not be null"
        );
    }

    /**
     * Returns Orchestrator status after validating required internal call context.
     *
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @return shared response containing Orchestrator service identity
     */
    public ApiResponse<InternalServiceStatus> getOrchestratorStatus(
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey
    ) {
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        ApiResponse<InternalServiceStatus> response = orchestratorClient.getStatus(
                requestId,
                tenantId,
                userId,
                idempotencyKey
        );
        if (response == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, EMPTY_DOWNSTREAM_RESPONSE_MESSAGE);
        }
        return response;
    }

    /**
     * Opens a Gateway SSE stream and forwards the internal Orchestrator stream through OpenFeign.
     *
     * @param request streaming model business payload
     * @param requestId propagated request identifier
     * @param tenantId tenant identifier
     * @param userId user identifier
     * @param idempotencyKey idempotency key for the internal call
     * @param sessionId stream session identifier
     * @param lastEventId optional SSE event position for reconnect
     * @return Gateway response body that streams Orchestrator SSE bytes to the caller
     */
    public ResponseEntity<StreamingResponseBody> streamModel(
            StreamingModelRequest request,
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            String lastEventId
    ) {
        StreamingModelRequest validatedRequest = requireRequest(request);
        requireContext(requestId, "requestId");
        requireContext(tenantId, "tenantId");
        requireContext(userId, "userId");
        requireContext(idempotencyKey, "idempotencyKey");
        requireContext(sessionId, "sessionId");
        return gatewaySentinelGuardService.guardModelStream(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                () -> openOrchestratorStream(
                        validatedRequest,
                        requestId,
                        tenantId,
                        userId,
                        idempotencyKey,
                        sessionId,
                        lastEventId
                )
        );
    }

    /**
     * Opens a browser-friendly Gateway model stream with generated local context.
     *
     * @param query user query from a browser address bar
     * @return Gateway response body that streams Orchestrator SSE bytes to the caller
     */
    public ResponseEntity<StreamingResponseBody> streamBrowserModel(String query) {
        StreamingModelRequest request = StreamingModelRequest.of(query);
        return streamModel(
                request,
                IdUtil.generate(CommonConstants.REQUEST_ID_PREFIX),
                CommonConstants.LOCAL_BROWSER_TENANT_ID,
                CommonConstants.LOCAL_BROWSER_USER_ID,
                IdUtil.generate(CommonConstants.IDEMPOTENCY_KEY_PREFIX),
                IdUtil.generate(CommonConstants.STREAM_SESSION_ID_PREFIX),
                null
        );
    }

    private ResponseEntity<StreamingResponseBody> openOrchestratorStream(
            StreamingModelRequest validatedRequest,
            String requestId,
            String tenantId,
            String userId,
            String idempotencyKey,
            String sessionId,
            String lastEventId
    ) {
        Response response = requireSuccessfulStream(orchestratorClient.streamModel(
                requestId,
                tenantId,
                userId,
                idempotencyKey,
                sessionId,
                lastEventId,
                validatedRequest
        ));
        return ResponseEntity.status(response.status())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamingResponseBody(response));
    }

    private static void requireContext(String value, String contextName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, contextName + MISSING_CONTEXT_MESSAGE_SUFFIX);
        }
    }

    private static StreamingModelRequest requireRequest(StreamingModelRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, EMPTY_STREAM_REQUEST_MESSAGE);
        }
        return request;
    }

    private static Response requireSuccessfulStream(Response response) {
        if (response == null || response.body() == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, EMPTY_DOWNSTREAM_STREAM_MESSAGE);
        }
        if (response.status() < MINIMUM_SUCCESS_STATUS || response.status() >= MAXIMUM_SUCCESS_STATUS_EXCLUSIVE) {
            closeQuietly(response);
            throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, UNAVAILABLE_STREAM_MESSAGE);
        }
        return response;
    }

    private static StreamingResponseBody streamingResponseBody(Response upstreamResponse) {
        return outputStream -> {
            try (Response response = upstreamResponse; InputStream inputStream = response.body().asInputStream()) {
                inputStream.transferTo(outputStream);
                outputStream.flush();
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE, STREAM_COPY_FAILURE_MESSAGE);
            }
        };
    }

    private static void closeQuietly(Response response) {
        try {
            response.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close unavailable Orchestrator stream response", exception);
        }
    }
}
