package com.ai.gateway.capability.annotation;

import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 声明公开输出字段的脱敏规则。
 */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CapRedaction {

    String path();

    CapabilityRedactionMethod method();
}
