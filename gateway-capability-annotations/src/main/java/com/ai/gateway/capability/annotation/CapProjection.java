package com.ai.gateway.capability.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 Provider 数据到公开输出的结构化投影。
 */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CapProjection {

    String from();

    String to();
}
