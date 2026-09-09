package com.claw4j.gateway;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.dto.StreamingModelRequest;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.client.OrchestratorClient;
import com.claw4j.gateway.controller.GatewayStreamingModelController;
import com.claw4j.gateway.service.GatewaySentinelGuardService;
import com.claw4j.gateway.service.OrchestratorGatewayService;
import feign.Request;
import feign.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the integrated browser-to-Gateway-to-Orchestrator model streaming chain.
 */
class GatewayModelStreamingChainContractTest {

    private static final String DOWNSTREAM_SSE = """
            id: 1
            event: token
            data: first

            id: 2
            event: fallback-start
            data: qwen

            id: 3
            event: adaptation
            data: summary

            id: 4
            event: failure
            data: provider

            id: 5
            event: complete
            data: done

            """;

    @Test
    void browserFriendlyEntryCreatesContextAndStreamsFromGateway() throws Exception {
        Method chatMethod = GatewayStreamingModelController.class.getDeclaredMethod("chat", String.class);
        GetMapping getMapping = chatMethod.getAnnotation(GetMapping.class);
        RecordingOrchestratorClient client = new RecordingOrchestratorClient(DOWNSTREAM_SSE);
        GatewayStreamingModelController controller = new GatewayStreamingModelController(gatewayService(client));

        ResponseEntity<StreamingResponseBody> response = controller.chat("hello");
        String body = writeBody(response);

        assertThat(getMapping.value()).containsExactly("/ai/chat");
        assertThat(getMapping.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(body).isEqualTo(DOWNSTREAM_SSE);
        assertThat(client.streamCallCount).isEqualTo(1);
        assertThat(client.lastRequestId).startsWith(CommonConstants.REQUEST_ID_PREFIX + CommonConstants.ID_SEPARATOR);
        assertThat(client.lastTenantId).isEqualTo(CommonConstants.LOCAL_BROWSER_TENANT_ID);
        assertThat(client.lastUserId).isEqualTo(CommonConstants.LOCAL_BROWSER_USER_ID);
        assertThat(client.lastIdempotencyKey)
                .startsWith(CommonConstants.IDEMPOTENCY_KEY_PREFIX + CommonConstants.ID_SEPARATOR);
        assertThat(client.lastSessionId)
                .startsWith(CommonConstants.STREAM_SESSION_ID_PREFIX + CommonConstants.ID_SEPARATOR);
        assertThat(client.lastLastEventId).isNull();
        assertThat(client.lastStreamRequest.getQuery()).isEqualTo("hello");
    }

    @Test
    void blankBrowserQueryFailsClosedBeforeOrchestratorIsInvoked() {
        RecordingOrchestratorClient client = new RecordingOrchestratorClient(DOWNSTREAM_SSE);
        GatewayStreamingModelController controller = new GatewayStreamingModelController(gatewayService(client));

        assertThatThrownBy(() -> controller.chat(" "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThat(client.streamCallCount).isZero();
    }

    @Test
    void formalStreamingApiRemainsHeaderDrivenWithModelFocusedPayload() throws Exception {
        Method streamMethod = GatewayStreamingModelController.class.getDeclaredMethod(
                "stream",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                StreamingModelRequest.class
        );
        PostMapping postMapping = streamMethod.getAnnotation(PostMapping.class);
        RecordingOrchestratorClient client = new RecordingOrchestratorClient(DOWNSTREAM_SSE);
        GatewayStreamingModelController controller = new GatewayStreamingModelController(gatewayService(client));

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                "req-formal",
                "tenant-formal",
                "user-formal",
                "idem-formal",
                "session-formal",
                "7",
                StreamingModelRequest.of("formal query")
        );

        assertThat(postMapping.value()).containsExactly("/api/model/stream");
        assertThat(postMapping.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(postMapping.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(writeBody(response)).isEqualTo(DOWNSTREAM_SSE);
        assertThat(client.lastRequestId).isEqualTo("req-formal");
        assertThat(client.lastTenantId).isEqualTo("tenant-formal");
        assertThat(client.lastUserId).isEqualTo("user-formal");
        assertThat(client.lastIdempotencyKey).isEqualTo("idem-formal");
        assertThat(client.lastSessionId).isEqualTo("session-formal");
        assertThat(client.lastLastEventId).isEqualTo("7");
        assertThat(client.lastStreamRequest.getQuery()).isEqualTo("formal query");
    }

    @Test
    void gatewayPreservesAllOrchestratorSseBytesWithoutParsing() throws IOException {
        RecordingOrchestratorClient client = new RecordingOrchestratorClient(DOWNSTREAM_SSE);
        OrchestratorGatewayService service = gatewayService(client);

        ResponseEntity<StreamingResponseBody> response = service.streamModel(
                StreamingModelRequest.of("preserve bytes"),
                "req-preserve",
                "tenant-preserve",
                "user-preserve",
                "idem-preserve",
                "session-preserve",
                "4"
        );

        assertThat(writeBody(response)).isEqualTo(DOWNSTREAM_SSE);
    }

    @Test
    void gatewayRateLimitBlocksStreamBeforeOrchestratorIsInvoked() {
        RecordingOrchestratorClient client = new RecordingOrchestratorClient(DOWNSTREAM_SSE);
        OrchestratorGatewayService service = new OrchestratorGatewayService(
                client,
                new BlockingGatewaySentinelGuardService()
        );

        assertThatThrownBy(() -> service.streamModel(
                StreamingModelRequest.of("blocked"),
                "req-blocked",
                "tenant-blocked",
                "user-blocked",
                "idem-blocked",
                "session-blocked",
                null
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RATE_LIMITED);

        assertThat(client.streamCallCount).isZero();
    }

    private static OrchestratorGatewayService gatewayService(RecordingOrchestratorClient client) {
        return new OrchestratorGatewayService(client, new PassthroughGatewaySentinelGuardService());
    }

    private static String writeBody(ResponseEntity<StreamingResponseBody> response) throws IOException {
        StreamingResponseBody body = response.getBody();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        assertThat(body).isNotNull();
        body.writeTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private static final class PassthroughGatewaySentinelGuardService extends GatewaySentinelGuardService {

        @Override
        public <T> T guardModelStream(
                String requestId,
                String tenantId,
                String userId,
                String idempotencyKey,
                Supplier<T> operation
        ) {
            return operation.get();
        }
    }

    private static final class BlockingGatewaySentinelGuardService extends GatewaySentinelGuardService {

        @Override
        public <T> T guardModelStream(
                String requestId,
                String tenantId,
                String userId,
                String idempotencyKey,
                Supplier<T> operation
        ) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Gateway Sentinel rate limit triggered");
        }
    }

    private static final class RecordingOrchestratorClient implements OrchestratorClient {

        private final String downstreamSse;
        private int streamCallCount;
        private String lastRequestId;
        private String lastTenantId;
        private String lastUserId;
        private String lastIdempotencyKey;
        private String lastSessionId;
        private String lastLastEventId;
        private StreamingModelRequest lastStreamRequest;

        private RecordingOrchestratorClient(String downstreamSse) {
            this.downstreamSse = downstreamSse;
        }

        @Override
        public ApiResponse<InternalServiceStatus> getStatus(
                String requestId,
                String tenantId,
                String userId,
                String idempotencyKey
        ) {
            return ApiResponse.success(InternalServiceStatus.of(
                    "claw4j-orchestrator",
                    "orchestrator",
                    8081,
                    requestId
            ));
        }

        @Override
        public Response streamModel(
                String requestId,
                String tenantId,
                String userId,
                String idempotencyKey,
                String sessionId,
                String lastEventId,
                StreamingModelRequest request
        ) {
            streamCallCount++;
            lastRequestId = requestId;
            lastTenantId = tenantId;
            lastUserId = userId;
            lastIdempotencyKey = idempotencyKey;
            lastSessionId = sessionId;
            lastLastEventId = lastEventId;
            lastStreamRequest = request;
            return Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(feignRequest())
                    .headers(Map.of("Content-Type", List.of(MediaType.TEXT_EVENT_STREAM_VALUE)))
                    .body(downstreamSse, StandardCharsets.UTF_8)
                    .build();
        }

        private static Request feignRequest() {
            return Request.create(
                    Request.HttpMethod.POST,
                    "/internal/orchestrator/model/stream",
                    Map.of(),
                    new byte[0],
                    StandardCharsets.UTF_8,
                    null
            );
        }
    }
}
