package com.claw4j.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes Claw4J tool metadata without replacing Spring AI tool annotations.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Tool {

    /**
     * Returns the Claw4J tool name.
     *
     * @return stable tool name
     */
    String name();

    /**
     * Returns the human-readable tool description.
     *
     * @return tool description
     */
    String description();

    /**
     * Returns whether calls to this tool are expected to be idempotent.
     *
     * @return true when the tool boundary is idempotent
     */
    boolean idempotent() default false;
}
