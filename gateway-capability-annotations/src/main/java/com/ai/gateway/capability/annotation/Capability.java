package com.ai.gateway.capability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将一个接口方法显式声明为候选能力。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Capability {

    String id();

    String version();

    CapabilityRisk risk();

    String policyRef();

    String displayName() default "";

    String description() default "";
}
