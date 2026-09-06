package com.claw4j.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Claw4J boundary that should carry audit metadata.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Audit {

    /**
     * Returns the audit action name.
     *
     * @return stable action identifier
     */
    String action();

    /**
     * Returns the audited resource name.
     *
     * @return stable resource identifier
     */
    String resource();
}
