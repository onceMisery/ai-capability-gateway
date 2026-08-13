package com.ai.gateway.testprovider;

import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dubbo implementation of {@link OrderQueryApi} (design document ).
 *
 * <p>Returns the platform standard Envelope {@code {code, value, message}} and supports
 * a set of deterministic test scenarios driven by the {@code orderNo} request field:</p>
 * <ul>
 * <li>normal orderNo &rarr; returns order data with {@code code=200}</li>
 * <li>missing/blank orderNo &rarr; business error {@code code=400}</li>
 * <li>{@code TIMEOUT} &rarr; sleeps to simulate a provider timeout</li>
 * <li>{@code ERROR} &rarr; throws a {@link RuntimeException}</li>
 * <li>{@code LARGE} &rarr; returns a large payload to exercise response size limits</li>
 * </ul>
 */
@DubboService
public class OrderQueryApiImpl implements OrderQueryApi {

    /** Sleep duration used to simulate a timeout scenario. */
    private static final long TIMEOUT_SLEEP_MILLIS = 10_000L;

    /** Number of rows emitted for the LARGE response scenario. */
    private static final int LARGE_RESPONSE_ROWS = 5_000;

    @Override
    public Map<String, Object> query(Long orgId, Map<String, Object> request) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Object orderNoValue = safeRequest.get("orderNo");
        String orderNo = orderNoValue == null ? null : orderNoValue.toString().trim();

        // Scenario: empty request -> business validation error.
        if (orderNo == null || orderNo.isEmpty()) {
            return envelope("400", null, "orderNo is required");
        }

        // Scenario: simulated provider timeout.
        if ("TIMEOUT".equals(orderNo)) {
            sleepQuietly(TIMEOUT_SLEEP_MILLIS);
            return envelope("200", orderData(orgId, orderNo), "success");
        }

        // Scenario: simulated unexpected provider failure.
        if ("ERROR".equals(orderNo)) {
            throw new RuntimeException("Simulated provider error for orderNo=ERROR");
        }

        // Scenario: large response to test size limits.
        if ("LARGE".equals(orderNo)) {
            return envelope("200", largeData(orgId), "success");
        }

        // Scenario: normal query.
        return envelope("200", orderData(orgId, orderNo), "success");
    }

    private Map<String, Object> orderData(Long orgId, String orderNo) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderNo", orderNo);
        value.put("status", "PAID");
        value.put("amount", new BigDecimal("199.99"));
        value.put("customerName", "Test Customer");
        value.put("orgId", orgId);
        return value;
    }

    private Map<String, Object> largeData(Long orgId) {
        List<Map<String, Object>> rows = new ArrayList<>(LARGE_RESPONSE_ROWS);
        for (int i = 0; i < LARGE_RESPONSE_ROWS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderNo", "LARGE-" + i);
            row.put("status", "PAID");
            row.put("amount", new BigDecimal("99.99"));
            row.put("customerName", "Customer-" + i);
            row.put("description", "Padding payload to enlarge the response body #" + i);
            rows.add(row);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orgId", orgId);
        value.put("total", LARGE_RESPONSE_ROWS);
        value.put("orders", rows);
        return value;
    }

    private static Map<String, Object> envelope(String code, Object value, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("value", value);
        envelope.put("message", message);
        return envelope;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
