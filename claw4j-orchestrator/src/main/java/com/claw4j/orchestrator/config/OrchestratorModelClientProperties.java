package com.claw4j.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds model-provider client selection settings.
 */
@Component
@ConfigurationProperties(prefix = "claw4j.model.client")
public class OrchestratorModelClientProperties {

    private ClientMode mode = ClientMode.DETERMINISTIC;

    /**
     * Returns the selected model client mode.
     *
     * @return selected model client mode
     */
    public ClientMode getMode() {
        return mode;
    }

    /**
     * Updates the selected model client mode.
     *
     * @param mode selected model client mode
     */
    public void setMode(ClientMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    /**
     * Returns whether real provider-backed calls are enabled.
     *
     * @return true when real provider-backed calls are enabled
     */
    public boolean isRealMode() {
        return ClientMode.REAL == mode;
    }

    /**
     * Model client modes supported by Orchestrator.
     */
    public enum ClientMode {
        DETERMINISTIC,
        REAL
    }
}
