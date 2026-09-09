package com.claw4j.orchestrator;

import com.claw4j.common.constant.CommonConstants;
import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.orchestrator.client.A2aBrokerClient;
import com.claw4j.orchestrator.client.A2aBrokerClientFallback;
import com.claw4j.orchestrator.client.OrchestratorFeignRequestContextInterceptor;
import com.claw4j.orchestrator.controller.OrchestratorInternalCallController;
import com.claw4j.orchestrator.service.A2aBrokerGatewayService;
import com.claw4j.orchestrator.service.OrchestratorStatusService;
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
 * Verifies Orchestrator's governed OpenFeign call contract.
 */
class OpenFeignServiceCallContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path APPLICATION_YML_PATH = Path.of("src/main/resources/application.yml");
    private static final Path CLIENT_PATH = Path.of("src/main/java/com/claw4j/orchestrator/client/A2aBrokerClient.java");
    private static final Path INTERCEPTOR_PATH = Path.of(
            "src/main/java/com/claw4j/orchestrator/client/OrchestratorFeignRequestContextInterceptor.java"
    );

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void applicationEnablesFeignClientsOnlyForOrchestratorClientPackage() {
        EnableFeignClients annotation = OrchestratorApplication.class.getAnnotation(EnableFeignClients.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.basePackages()).containsExactly("com.claw4j.orchestrator.client");
    }

    @Test
    void pomDeclaresOpenFeignLoadBalancerAndResilience4jWithoutSiblingServiceDependencies() throws IOException {
        String pom = Files.readString(POM_PATH);

        assertThat(pom).contains("<artifactId>spring-cloud-starter-openfeign</artifactId>");
        assertThat(pom).contains("<artifactId>spring-cloud-starter-loadbalancer</artifactId>");
        assertThat(pom).contains("<artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>spring-cloud-circuitbreaker-sentinel</artifactId>");
        assertThat(pom).doesNotContain("<version>5.0.0</version>");
        assertThat(pom).doesNotContain("<artifactId>claw4j-a2a-broker</artifactId>");
    }

    @Test
    void a2aBrokerClientUsesServiceNameFallbackAndNoFixedAddress() throws NoSuchMethodException, IOException {
        FeignClient annotation = A2aBrokerClient.class.getAnnotation(FeignClient.class);
        Method getStatus = A2aBrokerClient.class.getDeclaredMethod(
                "getStatus",
                String.class,
                String.class,
                String.class,
                String.class
        );
        GetMapping getMapping = getStatus.getAnnotation(GetMapping.class);
        String clientSource = Files.readString(CLIENT_PATH);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("claw4j-a2a-broker");
        assertThat(annotation.contextId()).isEqualTo("a2aBrokerClient");
        assertThat(annotation.fallback()).isEqualTo(A2aBrokerClientFallback.class);
        assertThat(getMapping.value()).containsExactly("/internal/a2a/status");
        assertThat(clientSource).doesNotContain("8084");
        assertThat(clientSource).doesNotContain("http://");
        assertThat(clientSource).doesNotContain("https://");
    }

    @Test
    void applicationConfigurationBoundsFeignCallsAndDisablesRetries() throws IOException {
        String applicationYaml = Files.readString(APPLICATION_YML_PATH);

        assertThat(applicationYaml).contains("openfeign:");
        assertThat(applicationYaml).contains("circuitbreaker:");
        assertThat(applicationYaml).contains("enabled: true");
        assertThat(applicationYaml).contains("claw4j-a2a-broker:");
        assertThat(applicationYaml).contains("connectTimeout: 1000");
        assertThat(applicationYaml).contains("readTimeout: 3000");
        assertThat(applicationYaml).doesNotContain("CLAW4J_A2A_BROKER_FEIGN");
    }

    @Test
    void providerServiceReturnsIdentityAndRejectsMissingContext() {
        OrchestratorStatusService service = new OrchestratorStatusService("claw4j-orchestrator", "orchestrator", 8081);
        RecordingA2aBrokerClient client = new RecordingA2aBrokerClient();
        OrchestratorInternalCallController controller = new OrchestratorInternalCallController(
                service,
                new A2aBrokerGatewayService(client)
        );

        ApiResponse<InternalServiceStatus> valid = controller.getStatus("req-1", "tenant-1", "user-1", "idem-1");
        ApiResponse<InternalServiceStatus> broker = controller.getA2aBrokerStatus("req-1", "tenant-1", "user-1", "idem-1");

        assertThat(valid.getData().getServiceName()).isEqualTo("claw4j-orchestrator");
        assertThat(valid.getData().getRequestId()).isEqualTo("req-1");
        assertThat(broker.getData().getServiceName()).isEqualTo("claw4j-a2a-broker");
        assertThat(client.callCount).isEqualTo(1);
        assertThatThrownBy(() -> service.getStatus("req-1", "tenant-1", " ", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void fallbackRaisesStableDownstreamUnavailableOutcome() {
        A2aBrokerClientFallback fallback = new A2aBrokerClientFallback();

        assertThatThrownBy(() -> fallback.getStatus("req-1", "tenant-1", "user-1", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOWNSTREAM_SERVICE_UNAVAILABLE);
    }

    @Test
    void serviceRejectsMissingContextBeforeCallingA2aBroker() {
        RecordingA2aBrokerClient client = new RecordingA2aBrokerClient();
        A2aBrokerGatewayService service = new A2aBrokerGatewayService(client);

        assertThatThrownBy(() -> service.getA2aBrokerStatus("req-1", "tenant-1", "", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(client.callCount).isZero();

        ApiResponse<InternalServiceStatus> response = service.getA2aBrokerStatus("req-1", "tenant-1", "user-1", "idem-1");

        assertThat(client.callCount).isEqualTo(1);
        assertThat(response.getData().getServiceName()).isEqualTo("claw4j-a2a-broker");
    }

    @Test
    void requestContextInterceptorPropagatesInternalCallHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.REQUEST_ID_HEADER, "req-1");
        request.addHeader(CommonConstants.TENANT_ID_HEADER, "tenant-1");
        request.addHeader(CommonConstants.USER_ID_HEADER, "user-1");
        request.addHeader(CommonConstants.IDEMPOTENCY_KEY_HEADER, "idem-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        new OrchestratorFeignRequestContextInterceptor().apply(template);

        assertHeader(template, CommonConstants.REQUEST_ID_HEADER, "req-1");
        assertHeader(template, CommonConstants.TENANT_ID_HEADER, "tenant-1");
        assertHeader(template, CommonConstants.USER_ID_HEADER, "user-1");
        assertHeader(template, CommonConstants.IDEMPOTENCY_KEY_HEADER, "idem-1");
        assertThat(new OrchestratorFeignRequestContextInterceptor().feignRetryer()).isSameAs(Retryer.NEVER_RETRY);
    }

    @Test
    void feignClientPreservesLoadBalancerAndBypassesSystemProxy() throws IOException {
        OrchestratorFeignRequestContextInterceptor interceptor = new OrchestratorFeignRequestContextInterceptor();

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

    private static final class RecordingA2aBrokerClient implements A2aBrokerClient {

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
                    "claw4j-a2a-broker",
                    "a2a-broker",
                    8084,
                    requestId
            ));
        }
    }
}
