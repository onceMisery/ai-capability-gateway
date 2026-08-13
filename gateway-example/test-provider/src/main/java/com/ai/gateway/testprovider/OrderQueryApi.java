package com.ai.gateway.testprovider;

import java.util.Map;

/**
 * Test Dubbo interface simulating a real business order query API
 * (design document ).
 *
 * <p>The signature intentionally uses only JDK types ({@code Long} and {@code Map})
 * so the gateway can invoke it through generic Dubbo invocation without needing the
 * original business API JAR. The returned {@code Map} carries the platform standard
 * Envelope structure: {@code {code, value, message}}.</p>
 */
public interface OrderQueryApi {

    /**
     * Queries a single order.
     *
     * @param orgId the organization / tenant id (injected by the gateway from the principal)
     * @param request the business request payload; expected to contain {@code orderNo}
     * @return a platform standard Envelope as a {@code Map}:
     * {@code {code: "200", value: {...}, message: "success"}} on success
     */
    Map<String, Object> query(Long orgId, Map<String, Object> request);
}
