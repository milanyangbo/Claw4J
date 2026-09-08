package com.claw4j.common;

import com.claw4j.common.annotation.Audit;
import com.claw4j.common.annotation.RateLimit;
import com.claw4j.common.annotation.Tool;
import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.ErrorResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.Claw4jException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.common.exception.GlobalExceptionHandler;
import com.claw4j.common.util.IdUtil;
import com.claw4j.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the shared contracts exposed by the common foundation module.
 */
class CommonFoundationContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");

    @Test
    void annotationsKeepRuntimeMetadataForCrossCuttingServices() throws NoSuchMethodException {
        Method method = AnnotatedService.class.getDeclaredMethod("invoke");

        Audit audit = method.getAnnotation(Audit.class);
        Tool tool = method.getAnnotation(Tool.class);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        assertThat(audit.action()).isEqualTo("tool.invoke");
        assertThat(audit.resource()).isEqualTo("tool");
        assertThat(tool.name()).isEqualTo("calculator");
        assertThat(tool.description()).isEqualTo("Runs deterministic calculations");
        assertThat(tool.idempotent()).isTrue();
        assertThat(rateLimit.key()).isEqualTo("tenant:user:tool");
        assertThat(rateLimit.maxRequests()).isEqualTo(10);
        assertThat(rateLimit.windowSeconds()).isEqualTo(60);
    }

    @Test
    void responseDtosExposeStableSuccessAndErrorShapes() {
        ApiResponse<String> success = ApiResponse.success("payload");
        ErrorResponse error = ErrorResponse.of(ErrorCode.INVALID_REQUEST, "Invalid input", "req-123");

        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getMessage()).isEqualTo(CommonConstants.DEFAULT_SUCCESS_MESSAGE);
        assertThat(success.getData()).isEqualTo("payload");
        assertThat(error.isSuccess()).isFalse();
        assertThat(error.getCode()).isEqualTo(ErrorCode.INVALID_REQUEST.getCode());
        assertThat(error.getMessage()).isEqualTo("Invalid input");
        assertThat(error.getRequestId()).isEqualTo("req-123");
    }

    @Test
    void apiResponseDeserializesInternalStatusEnvelopeForFeignClients() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ApiResponse<InternalServiceStatus> response = ApiResponse.success(InternalServiceStatus.of(
                "claw4j-orchestrator",
                "orchestrator",
                8081,
                "req-feign"
        ));

        String json = objectMapper.writeValueAsString(response);
        ApiResponse<InternalServiceStatus> decoded = objectMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        assertThat(decoded.isSuccess()).isTrue();
        assertThat(decoded.getMessage()).isEqualTo(CommonConstants.DEFAULT_SUCCESS_MESSAGE);
        assertThat(decoded.getData().getServiceName()).isEqualTo("claw4j-orchestrator");
        assertThat(decoded.getData().getRequestId()).isEqualTo("req-feign");
    }

    @Test
    void internalCallContractsExposeRequiredHeadersAndUnavailableErrorCode() {
        assertThat(CommonConstants.REQUEST_ID_HEADER).isEqualTo("X-Request-Id");
        assertThat(CommonConstants.TENANT_ID_HEADER).isEqualTo("X-Tenant-Id");
        assertThat(CommonConstants.USER_ID_HEADER).isEqualTo("X-User-Id");
        assertThat(CommonConstants.IDEMPOTENCY_KEY_HEADER).isEqualTo("X-Idempotency-Key");
        assertThat(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE.getCode()).isEqualTo("CLAW4J-RPC-001");
        assertThat(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE.getMessage()).isEqualTo("Downstream service unavailable");
        assertThat(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.RATE_LIMITED.getCode()).isEqualTo("CLAW4J-SENTINEL-429");
        assertThat(ErrorCode.RATE_LIMITED.getMessage()).isEqualTo("Request rate limit exceeded");
        assertThat(ErrorCode.RATE_LIMITED.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.CIRCUIT_OPEN.getCode()).isEqualTo("CLAW4J-SENTINEL-503");
        assertThat(ErrorCode.CIRCUIT_OPEN.getMessage()).isEqualTo("Circuit breaker is open");
        assertThat(ErrorCode.CIRCUIT_OPEN.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void commonModuleDoesNotDeclareSentinelRuntimeDependency() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).doesNotContain("spring-cloud-starter-alibaba-sentinel");
        assertThat(pom).doesNotContain("sentinel-datasource-nacos");
        assertThat(pom).doesNotContain("sentinel-core");
    }

    @Test
    void internalServiceStatusRequiresTraceableServiceIdentity() {
        InternalServiceStatus status = InternalServiceStatus.of(
                "claw4j-orchestrator",
                "orchestrator",
                8081,
                "req-789"
        );

        assertThat(status.getServiceName()).isEqualTo("claw4j-orchestrator");
        assertThat(status.getRole()).isEqualTo("orchestrator");
        assertThat(status.getInstancePort()).isEqualTo(8081);
        assertThat(status.getRequestId()).isEqualTo("req-789");
        assertThatThrownBy(() -> InternalServiceStatus.of("", "orchestrator", 8081, "req-789"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> InternalServiceStatus.of("claw4j-orchestrator", "orchestrator", 0, "req-789"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> InternalServiceStatus.of("claw4j-orchestrator", "orchestrator", 8081, " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void globalExceptionHandlerMapsKnownBusinessErrors() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.REQUEST_ID_HEADER, "req-456");

        ResponseEntity<ErrorResponse> response = handler.handleClaw4jException(
                new BusinessException(ErrorCode.INVALID_REQUEST, "Bad request data"),
                new ServletWebRequest(request)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INVALID_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("Bad request data");
        assertThat(response.getBody().getRequestId()).isEqualTo("req-456");
    }

    @Test
    void globalExceptionHandlerSanitizesUnexpectedErrors() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new IllegalStateException("database password leaked"),
                new ServletWebRequest(new MockHttpServletRequest())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
        assertThat(response.getBody().getMessage()).doesNotContain("leaked");
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    @Test
    void jsonUtilitySerializesValuesAndRaisesCommonExceptionForInvalidJson() {
        String json = JsonUtil.toJson(Map.of("name", "claw4j"));

        assertThat(json).contains("\"name\":\"claw4j\"");
        assertThatThrownBy(() -> JsonUtil.fromJson("{broken", Map.class))
                .isInstanceOf(Claw4jException.class)
                .hasMessageContaining("deserialization");
    }

    @Test
    void idUtilityGeneratesIdentifiersWithOptionalContextPrefix() {
        String generated = IdUtil.generate();
        String prefixed = IdUtil.generate("req");

        assertThat(generated).isNotBlank();
        assertThat(prefixed).startsWith("req-");
        assertThat(prefixed.length()).isGreaterThan("req-".length());
        assertThatThrownBy(() -> IdUtil.generate("bad prefix"))
                .isInstanceOf(BusinessException.class);
    }

    private static final class AnnotatedService {

        @Audit(action = "tool.invoke", resource = "tool")
        @Tool(name = "calculator", description = "Runs deterministic calculations", idempotent = true)
        @RateLimit(key = "tenant:user:tool", maxRequests = 10, windowSeconds = 60)
        private void invoke() {
        }
    }
}
