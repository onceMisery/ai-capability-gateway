package com.ai.gateway.testprovider;

import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link OrderQueryApi} 的 Dubbo 实现（设计文档 §21.2）。
 *
 * <p>返回平台标准 Envelope {@code {code, value, message}}，并支持一组由请求字段
 * {@code orderNo} 驱动的确定性测试场景：</p>
 * <ul>
 * <li>普通 orderNo &rarr; 返回 {@code code=200} 的订单数据</li>
 * <li>缺失/空白 orderNo &rarr; 业务错误 {@code code=400}</li>
 * <li>{@code TIMEOUT} &rarr; 休眠以模拟 Provider 超时</li>
 * <li>{@code ERROR} &rarr; 抛出 {@link RuntimeException}</li>
 * <li>{@code LARGE} &rarr; 返回大体积响应以验证响应体大小限制</li>
 * </ul>
 *
 * @author cmiracle@163.com
 */
@DubboService(version = "1.0.0")
public class OrderQueryApiImpl implements OrderQueryApi {

    /** 模拟超时场景时使用的休眠时长。 */
    private static final long TIMEOUT_SLEEP_MILLIS = 10_000L;

    /** LARGE 响应场景下返回的行数。 */
    private static final int LARGE_RESPONSE_ROWS = 5_000;

    @Override
    public Map<String, Object> query(Long orgId, Map<String, Object> request) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Object orderNoValue = safeRequest.get("orderNo");
        String orderNo = orderNoValue == null ? null : orderNoValue.toString().trim();

        // 场景：请求为空 -> 业务校验错误。
        if (orderNo == null || orderNo.isEmpty()) {
            return envelope("400", null, "orderNo is required");
        }

        // 场景：模拟 Provider 超时。
        if ("TIMEOUT".equals(orderNo)) {
            sleepQuietly(TIMEOUT_SLEEP_MILLIS);
            return envelope("200", orderData(orgId, orderNo), "success");
        }

        // 场景：模拟 Provider 意外失败。
        if ("ERROR".equals(orderNo)) {
            throw new RuntimeException("Simulated provider error for orderNo=ERROR");
        }

        // 场景：大体积响应，用于验证大小限制。
        if ("LARGE".equals(orderNo)) {
            return envelope("200", largeData(orgId), "success");
        }

        // 场景：普通查询。
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
