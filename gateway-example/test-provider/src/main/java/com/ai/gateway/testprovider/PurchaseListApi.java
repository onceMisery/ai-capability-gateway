package com.ai.gateway.testprovider;

import java.util.Map;

/**
 * Second test Dubbo interface used for multi-capability testing
 * (design document ).
 *
 * <p>Simulates a paginated purchase list business API using only JDK types so the
 * gateway can invoke it generically. The returned {@code Map} carries the platform
 * standard Envelope structure {@code {code, value, message}} where {@code value}
 * holds paginated list data.</p>
 */
public interface PurchaseListApi {

    /**
     * Queries a paginated purchase list.
     *
     * @param orgId the organization / tenant id (injected by the gateway from the principal)
     * @param request the business request payload; may contain {@code pageNo}, {@code pageSize}
     * and optional filter fields
     * @return a platform standard Envelope as a {@code Map} whose {@code value} contains
     * {@code total}, {@code pageNo}, {@code pageSize} and {@code records}
     */
    Map<String, Object> queryList(Long orgId, Map<String, Object> request);
}
