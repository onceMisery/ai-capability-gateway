package com.ai.gateway.testprovider;

import java.util.Map;

/**
 * 用于多能力测试的第二个 Dubbo 测试接口（设计文档 §21.2）。
 *
 * <p>仅使用 JDK 类型模拟一个分页采购单业务 API，使网关可以以泛化方式调用。返回的
 * {@code Map} 遵循平台标准 Envelope 结构 {@code {code, value, message}}，其中
 * {@code value} 承载分页列表数据。</p>
 *
 * @author cmiracle@163.com
 */
public interface PurchaseListApi {

    /**
     * 查询分页采购单列表。
     *
     * @param orgId 组织/租户标识，由网关从 Principal 注入
     * @param request 业务请求体，可包含 {@code pageNo}、{@code pageSize} 及可选过滤字段
     * @return 平台标准 Envelope（{@code Map} 形式），其 {@code value} 包含
     * {@code total}、{@code pageNo}、{@code pageSize} 与 {@code records}
     */
    Map<String, Object> queryList(Long orgId, Map<String, Object> request);
}
