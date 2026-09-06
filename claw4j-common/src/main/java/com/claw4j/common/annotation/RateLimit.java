package com.claw4j.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Claw4J boundary with rate-limit metadata.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimit {

    /**
     * Returns the rate-limit key template.
     *
     * @return limit key identifier
     */
    String key();

    /**
     * Returns the maximum number of requests allowed in the window.
     *
     * @return maximum request count
     */
    int maxRequests();

    /**
     * Returns the rate-limit window in seconds.
     *
     * @return window length in seconds
     */
    int windowSeconds();
}
