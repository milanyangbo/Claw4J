package com.claw4j.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Holds refreshable Gateway demo configuration values loaded from local or Nacos Config sources.
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "claw4j.dynamic-config.gateway")
public class GatewayDynamicConfigProperties {

    private static final String DEFAULT_THRESHOLD = "10";
    private static final String DEFAULT_MESSAGE = "local-default";

    private String smokeRateLimitThreshold = DEFAULT_THRESHOLD;
    private String demoMessage = DEFAULT_MESSAGE;

    /**
     * Returns the raw smoke-test threshold value.
     *
     * @return raw threshold value
     */
    public String getSmokeRateLimitThreshold() {
        return smokeRateLimitThreshold;
    }

    /**
     * Updates the raw smoke-test threshold value.
     *
     * @param smokeRateLimitThreshold raw threshold value
     */
    public void setSmokeRateLimitThreshold(String smokeRateLimitThreshold) {
        this.smokeRateLimitThreshold = defaultIfNull(smokeRateLimitThreshold);
    }

    /**
     * Returns the demo message used to show hot refresh.
     *
     * @return demo message
     */
    public String getDemoMessage() {
        return demoMessage;
    }

    /**
     * Updates the demo message used to show hot refresh.
     *
     * @param demoMessage demo message
     */
    public void setDemoMessage(String demoMessage) {
        this.demoMessage = defaultIfBlank(demoMessage, DEFAULT_MESSAGE);
    }

    private static String defaultIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
