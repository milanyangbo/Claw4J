package com.claw4j.gateway.service;

import com.claw4j.gateway.config.GatewayDynamicConfigProperties;
import com.claw4j.gateway.dto.DynamicConfigStatus;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves Gateway dynamic configuration into safe effective values.
 */
@Service
public class GatewayDynamicConfigService {

    private static final String THRESHOLD_KEY = "claw4j.dynamic-config.gateway.smoke-rate-limit-threshold";
    private static final int UNSET_LAST_VALID_THRESHOLD = 0;
    private static final int DEFAULT_THRESHOLD = 10;
    private static final int MINIMUM_THRESHOLD = 1;
    private static final int MAXIMUM_THRESHOLD = 10000;

    private final Supplier<GatewayDynamicConfigProperties> propertiesSupplier;
    private final AtomicInteger lastValidThreshold = new AtomicInteger(UNSET_LAST_VALID_THRESHOLD);

    /**
     * Creates the Gateway dynamic configuration service.
     *
     * @param properties refreshable Gateway dynamic configuration properties
     */
    @Autowired
    public GatewayDynamicConfigService(GatewayDynamicConfigProperties properties) {
        this(() -> properties);
    }

    /**
     * Creates the Gateway dynamic configuration service with a dynamic properties supplier.
     *
     * @param propertiesSupplier supplier for current Gateway dynamic configuration properties
     */
    public GatewayDynamicConfigService(Supplier<GatewayDynamicConfigProperties> propertiesSupplier) {
        this.propertiesSupplier = Objects.requireNonNull(propertiesSupplier, "propertiesSupplier must not be null");
    }

    /**
     * Returns the current effective dynamic configuration status.
     *
     * @return dynamic configuration status
     */
    public DynamicConfigStatus currentStatus() {
        GatewayDynamicConfigProperties properties = Objects.requireNonNull(
                propertiesSupplier.get(),
                "propertiesSupplier must not return null"
        );
        ParseResult currentThreshold = parseThreshold(properties.getSmokeRateLimitThreshold());
        if (currentThreshold.valid()) {
            lastValidThreshold.set(currentThreshold.value());
            return createStatus(properties, currentThreshold.value(), false, "");
        }
        return createStatus(
                properties,
                fallbackThreshold(properties),
                true,
                THRESHOLD_KEY
        );
    }

    private DynamicConfigStatus createStatus(
            GatewayDynamicConfigProperties properties,
            int effectiveThreshold,
            boolean fallbackApplied,
            String invalidKey
    ) {
        return DynamicConfigStatus.of(
                effectiveThreshold,
                properties.getDemoMessage(),
                fallbackApplied,
                invalidKey
        );
    }

    private int fallbackThreshold(GatewayDynamicConfigProperties properties) {
        int lastValid = lastValidThreshold.get();
        if (lastValid >= MINIMUM_THRESHOLD) {
            return lastValid;
        }
        return DEFAULT_THRESHOLD;
    }

    private static ParseResult parseThreshold(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return ParseResult.invalid();
        }
        try {
            int parsedValue = Integer.parseInt(rawValue.trim());
            if (parsedValue < MINIMUM_THRESHOLD) {
                return ParseResult.invalid();
            }
            if (parsedValue > MAXIMUM_THRESHOLD) {
                return ParseResult.invalid();
            }
            return ParseResult.valid(parsedValue);
        } catch (NumberFormatException exception) {
            return ParseResult.invalid();
        }
    }

    private record ParseResult(boolean valid, int value) {

        private static ParseResult valid(int value) {
            return new ParseResult(true, value);
        }

        private static ParseResult invalid() {
            return new ParseResult(false, UNSET_LAST_VALID_THRESHOLD);
        }
    }
}
