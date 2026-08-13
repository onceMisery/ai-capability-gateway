package com.ai.gateway.testprovider;

import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dubbo implementation of {@link PurchaseListApi} (design document ).
 *
 * <p>Returns paginated purchase list data wrapped in the platform standard Envelope
 * {@code {code, value, message}}. Pagination is controlled by the {@code pageNo} and
 * {@code pageSize} request fields (defaulting to page 1 / size 10).</p>
 */
@DubboService
public class PurchaseListApiImpl implements PurchaseListApi {

    /** Default page number when not supplied by the request. */
    private static final int DEFAULT_PAGE_NO = 1;

    /** Default page size when not supplied by the request. */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** Total number of synthetic purchase records available. */
    private static final int TOTAL_RECORDS = 35;

    @Override
    public Map<String, Object> queryList(Long orgId, Map<String, Object> request) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        int pageNo = positiveInt(safeRequest.get("pageNo"), DEFAULT_PAGE_NO);
        int pageSize = positiveInt(safeRequest.get("pageSize"), DEFAULT_PAGE_SIZE);

        int fromIndex = Math.min((pageNo - 1) * pageSize, TOTAL_RECORDS);
        int toIndex = Math.min(fromIndex + pageSize, TOTAL_RECORDS);

        List<Map<String, Object>> records = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            records.add(purchaseRecord(orgId, i));
        }

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("total", TOTAL_RECORDS);
        value.put("pageNo", pageNo);
        value.put("pageSize", pageSize);
        value.put("records", records);

        return envelope("200", value, "success");
    }

    private Map<String, Object> purchaseRecord(Long orgId, int index) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("purchaseNo", "PO-" + String.format("%06d", index));
        record.put("supplierName", "Supplier-" + index);
        record.put("amount", new BigDecimal("1000.00").add(new BigDecimal(index)));
        record.put("status", index % 2 == 0 ? "APPROVED" : "PENDING");
        record.put("orgId", orgId);
        return record;
    }

    private static int positiveInt(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.toString().trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, Object> envelope(String code, Object value, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code);
        envelope.put("value", value);
        envelope.put("message", message);
        return envelope;
    }
}
