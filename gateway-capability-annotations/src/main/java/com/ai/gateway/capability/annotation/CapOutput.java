package com.ai.gateway.capability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明方法的公开输出契约。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CapOutput {

    CapabilityOutputMode mode();

    String schemaResource();

    String envelopeProfile() default "";

    CapProjection[] projection() default {};

    CapRedaction[] redactions() default {};

    int maxBytes() default 262144;
}
