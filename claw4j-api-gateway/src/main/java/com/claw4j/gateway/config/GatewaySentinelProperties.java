package com.claw4j.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds Gateway Sentinel proof-path rule defaults and resource names.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.sentinel.gateway")
public class GatewaySentinelProperties {

    private static final String DEFAULT_RESOURCE_NAME = "claw4j-gateway-ingress";
    private static final double DEFAULT_GLOBAL_QPS_THRESHOLD = 2.0D;
    private static final double DEFAULT_TENANT_QPS_THRESHOLD = 1.0D;

    private String resourceName = DEFAULT_RESOURCE_NAME;
    private double globalQpsThreshold = DEFAULT_GLOBAL_QPS_THRESHOLD;
    private double tenantQpsThreshold = DEFAULT_TENANT_QPS_THRESHOLD;

    /**
     * Returns the protected Gateway resource name.
     *
     * @return Sentinel resource name
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * Updates the protected Gateway resource name.
     *
     * @param resourceName Sentinel resource name
     */
    public void setResourceName(String resourceName) {
        this.resourceName = defaultIfBlank(resourceName, DEFAULT_RESOURCE_NAME);
    }

    /**
     * Returns the default global Gateway QPS threshold.
     *
     * @return global QPS threshold
     */
    public double getGlobalQpsThreshold() {
        return globalQpsThreshold;
    }

    /**
     * Updates the default global Gateway QPS threshold.
     *
     * @param globalQpsThreshold global QPS threshold
     */
    public void setGlobalQpsThreshold(double globalQpsThreshold) {
        this.globalQpsThreshold = positiveOrDefault(globalQpsThreshold, DEFAULT_GLOBAL_QPS_THRESHOLD);
    }

    /**
     * Returns the default tenant-scoped Gateway QPS threshold.
     *
     * @return tenant QPS threshold
     */
    public double getTenantQpsThreshold() {
        return tenantQpsThreshold;
    }

    /**
     * Updates the default tenant-scoped Gateway QPS threshold.
     *
     * @param tenantQpsThreshold tenant QPS threshold
     */
    public void setTenantQpsThreshold(double tenantQpsThreshold) {
        this.tenantQpsThreshold = positiveOrDefault(tenantQpsThreshold, DEFAULT_TENANT_QPS_THRESHOLD);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static double positiveOrDefault(double value, double defaultValue) {
        if (value <= 0.0D) {
            return defaultValue;
        }
        return value;
    }
}
