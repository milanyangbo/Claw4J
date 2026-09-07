package com.claw4j.gateway;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.gateway.client.GatewayFeignRequestContextInterceptor;
import com.claw4j.gateway.client.OrchestratorClient;
import com.claw4j.gateway.client.OrchestratorClientFallback;
import com.claw4j.gateway.controller.GatewayInternalCallController;
import com.claw4j.gateway.service.OrchestratorGatewayService;
import feign.Client;
import feign.RequestTemplate;
import feign.Retryer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies Gateway's governed OpenFeign call contract.
 */
class OpenFeignServiceCallContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path CLIENT_PATH = Path.of("src/main/java/com/claw4j/gateway/client/OrchestratorClient.java");
    private static final Path INTERCEPTOR_PATH = Path.of(
            "src/main/java/com/claw4j/gateway/client/GatewayFeignRequestContextInterceptor.java"
    );

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void applicationEnablesFeignClientsOnlyForGatewayClientPackage() {
        EnableFeignClients annotation = GatewayApplication.class.getAnnotation(EnableFeignClients.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.basePackages()).containsExactly("com.claw4j.gateway.client");
    }

    @Test
    void pomDeclaresOpenFeignLoadBalancerAndSentinelWithoutSiblingServiceDependencies() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-openfeign</artifactId>");
        assertThat(pom).contains("<artifactId>spring-cloud-starter-loadbalancer</artifactId>");
        assertThat(pom).contains("<artifactId>spring-cloud-circuitbreaker-sentinel</artifactId>");
        assertThat(pom).doesNotContain("<version>5.0.0</version>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-orchestrator</artifactId>");
    }

    @Test
    void orchestratorClientUsesServiceNameFallbackAndNoFixedAddress() throws NoSuchMethodException, IOException {
        FeignClient annotation = OrchestratorClient.class.getAnnotation(FeignClient.class);
        Method getStatus = OrchestratorClient.class.getDeclaredMethod(
                "getStatus",
                String.class,
                String.class,
                String.class,
                String.class
        );
        GetMapping getMapping = getStatus.getAnnotation(GetMapping.class);
        String clientSource = Files.readString(CLIENT_PATH);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("claw4j-orchestrator");
        assertThat(annotation.contextId()).isEqualTo("orchestratorClient");
        assertThat(annotation.fallback()).isEqualTo(OrchestratorClientFallback.class);
        assertThat(getMapping.value()).containsExactly("/internal/orchestrator/status");
        assertThat(clientSource).doesNotContain("8081");
        assertThat(clientSource).doesNotContain("http://");
        assertThat(clientSource).doesNotContain("https://");
    }

    @Test
    void applicationConfigurationBoundsFeignCallsAndDisablesRetries() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("openfeign:");
        assertThat(applicationYaml).contains("circuitbreaker:");
        assertThat(applicationYaml).contains("enabled: ${CLAW4J_FEIGN_CIRCUITBREAKER_ENABLED:true}");
        assertThat(applicationYaml).contains("claw4j-orchestrator:");
        assertThat(applicationYaml).contains("connectTimeout: ${CLAW4J_ORCHESTRATOR_FEIGN_CONNECT_TIMEOUT_MS:1000}");
        assertThat(applicationYaml).contains("readTimeout: ${CLAW4J_ORCHESTRATOR_FEIGN_READ_TIMEOUT_MS:3000}");
    }

    @Test
    void fallbackRaisesStableDownstreamUnavailableOutcome() {
        OrchestratorClientFallback fallback = new OrchestratorClientFallback();

        assertThatThrownBy(() -> fallback.getStatus("req-1", "tenant-1", "user-1", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE);
    }

    @Test
    void serviceRejectsMissingContextBeforeCallingOrchestrator() {
        RecordingOrchestratorClient client = new RecordingOrchestratorClient();
        OrchestratorGatewayService service = new OrchestratorGatewayService(client);

        assertThatThrownBy(() -> service.getOrchestratorStatus("req-1", "tenant-1", "", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(client.callCount).isZero();

        ApiResponse<InternalServiceStatus> response = service.getOrchestratorStatus("req-1", "tenant-1", "user-1", "idem-1");

        assertThat(client.callCount).isEqualTo(1);
        assertThat(response.getData().getServiceName()).isEqualTo("claw4j-orchestrator");
    }

    @Test
    void controllerDelegatesToGatewayService() {
        RecordingOrchestratorClient client = new RecordingOrchestratorClient();
        GatewayInternalCallController controller = new GatewayInternalCallController(new OrchestratorGatewayService(client));

        ApiResponse<InternalServiceStatus> response = controller.getOrchestratorStatus(
                "req-1",
                "tenant-1",
                "user-1",
                "idem-1"
        );

        assertThat(client.callCount).isEqualTo(1);
        assertThat(response.getData().getServiceName()).isEqualTo("claw4j-orchestrator");
    }

    @Test
    void requestContextInterceptorPropagatesInternalCallHeadersAndDisablesRetries() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.REQUEST_ID_HEADER, "req-1");
        request.addHeader(CommonConstants.TENANT_ID_HEADER, "tenant-1");
        request.addHeader(CommonConstants.USER_ID_HEADER, "user-1");
        request.addHeader(CommonConstants.IDEMPOTENCY_KEY_HEADER, "idem-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        new GatewayFeignRequestContextInterceptor().apply(template);

        assertHeader(template, CommonConstants.REQUEST_ID_HEADER, "req-1");
        assertHeader(template, CommonConstants.TENANT_ID_HEADER, "tenant-1");
        assertHeader(template, CommonConstants.USER_ID_HEADER, "user-1");
        assertHeader(template, CommonConstants.IDEMPOTENCY_KEY_HEADER, "idem-1");
        assertThat(new GatewayFeignRequestContextInterceptor().feignRetryer()).isSameAs(Retryer.NEVER_RETRY);
    }

    @Test
    void feignClientPreservesLoadBalancerAndBypassesSystemProxy() throws IOException {
        GatewayFeignRequestContextInterceptor interceptor = new GatewayFeignRequestContextInterceptor();

        Client client = interceptor.feignClient(
                mock(LoadBalancerClient.class),
                mock(LoadBalancerClientFactory.class),
                List.of()
        );

        assertThat(client).isInstanceOf(FeignBlockingLoadBalancerClient.class);
        assertThat(((FeignBlockingLoadBalancerClient) client).getDelegate().getClass().getSimpleName())
                .isEqualTo("NoProxyFeignClient");
        assertThat(Files.readString(INTERCEPTOR_PATH)).contains("Proxy.NO_PROXY");
    }

    private static void assertHeader(RequestTemplate template, String headerName, String expectedValue) {
        Collection<String> values = template.headers().get(headerName);
        assertThat(values).containsExactly(expectedValue);
    }

    private static final class RecordingOrchestratorClient implements OrchestratorClient {

        private int callCount;

        @Override
        public ApiResponse<InternalServiceStatus> getStatus(
                String requestId,
                String tenantId,
                String userId,
                String idempotencyKey
        ) {
            callCount++;
            return ApiResponse.success(InternalServiceStatus.of(
                    "claw4j-orchestrator",
                    "orchestrator",
                    8081,
                    requestId
            ));
        }
    }
}
