package com.ai.gateway.capability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明复合 DTO 中单个目标字段的来源。
 */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CapFieldBinding {

    String targetPath();

    CapabilityArgumentSource source();

    String sourcePath() default "";

    String converter() default "";

    String constantValueJson() default "";
}
