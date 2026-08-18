package com.ai.gateway.capability.annotation;

/**
 * 对外输出脱敏方式。
 */
public enum CapabilityRedactionMethod {
    DELETE,
    PARTIAL_MASK,
    HASH
}
