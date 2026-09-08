package com.claw4j.gateway.client;

import com.claw4j.common.constant.CommonConstants;
import feign.Client;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Retryer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Propagates Gateway request context into outbound Feign calls.
 */
@Component
public class GatewayFeignRequestContextInterceptor implements RequestInterceptor {

    private static final List<String> PROPAGATED_HEADERS = List.of(
            CommonConstants.REQUEST_ID_HEADER,
            CommonConstants.TENANT_ID_HEADER,
            CommonConstants.USER_ID_HEADER,
            CommonConstants.IDEMPOTENCY_KEY_HEADER,
            CommonConstants.STREAM_SESSION_ID_HEADER,
            CommonConstants.LAST_EVENT_ID_HEADER
    );

    /**
     * Copies approved request headers into the outbound Feign request when present.
     *
     * @param template outbound Feign request template
     */
    @Override
    public void apply(RequestTemplate template) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return;
        }
        HttpServletRequest request = servletRequestAttributes.getRequest();
        for (String headerName : PROPAGATED_HEADERS) {
            copyHeader(request, template, headerName);
        }
    }

    /**
     * Disables OpenFeign retries by default for internal calls.
     *
     * @return Feign retryer that never retries failed calls
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * Creates a load-balanced Feign client that bypasses JVM/system HTTP proxies for internal service calls.
     *
     * @param loadBalancerClient client used to choose discovered service instances
     * @param loadBalancerClientFactory factory for per-service load-balancer configuration
     * @param transformers request transformers applied after instance selection
     * @return Feign client that preserves service discovery while avoiding external proxy routing
     */
    @Bean
    public Client feignClient(
            LoadBalancerClient loadBalancerClient,
            LoadBalancerClientFactory loadBalancerClientFactory,
            List<LoadBalancerFeignRequestTransformer> transformers
    ) {
        return new FeignBlockingLoadBalancerClient(
                new NoProxyFeignClient(),
                loadBalancerClient,
                loadBalancerClientFactory,
                transformers
        );
    }

    private static void copyHeader(HttpServletRequest request, RequestTemplate template, String headerName) {
        String headerValue = request.getHeader(headerName);
        if (headerValue != null && !headerValue.isBlank()) {
            template.header(headerName, headerValue);
        }
    }

    /**
     * Opens internal Feign HTTP connections without using JVM or desktop proxy settings.
     */
    private static final class NoProxyFeignClient extends Client.Default {

        private NoProxyFeignClient() {
            super(null, null);
        }

        /**
         * Opens the target URL directly after Spring Cloud LoadBalancer reconstructs the service-instance URI.
         *
         * @param url selected downstream service-instance URL
         * @return direct HTTP connection with no proxy
         * @throws IOException when the connection cannot be opened
         */
        @Override
        public HttpURLConnection getConnection(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
    }
}
