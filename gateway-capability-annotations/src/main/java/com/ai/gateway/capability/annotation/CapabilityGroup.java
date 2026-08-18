package com.ai.gateway.capability.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所属的能力分组和技术协议，不代表接口本身可被发布。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CapabilityGroup {

    String idPrefix();

    CapabilityProtocol protocol();
}
