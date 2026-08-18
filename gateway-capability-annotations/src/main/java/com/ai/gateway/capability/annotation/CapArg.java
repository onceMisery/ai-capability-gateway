package com.ai.gateway.capability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明简单方法参数的可信来源。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface CapArg {

    CapabilityArgumentSource source();

    String name() default "";

    String sourcePath() default "";

    String converter() default "";

    String constantValueJson() default "";
}
