package com.ai.gateway.testprovider;

import com.ai.gateway.capability.annotation.CapArg;
import com.ai.gateway.capability.annotation.CapComposite;
import com.ai.gateway.capability.annotation.CapFieldBinding;
import com.ai.gateway.capability.annotation.CapInput;
import com.ai.gateway.capability.annotation.CapOutput;
import com.ai.gateway.capability.annotation.CapProjection;
import com.ai.gateway.capability.annotation.CapRedaction;
import com.ai.gateway.capability.annotation.Capability;
import com.ai.gateway.capability.annotation.CapabilityArgumentSource;
import com.ai.gateway.capability.annotation.CapabilityGroup;
import com.ai.gateway.capability.annotation.CapabilityOutputMode;
import com.ai.gateway.capability.annotation.CapabilityProtocol;
import com.ai.gateway.capability.annotation.CapabilityRedactionMethod;
import com.ai.gateway.capability.annotation.CapabilityRisk;

import java.util.Map;

/**
 * 模拟真实订单查询场景的 Dubbo 测试接口。
 *
 * <p>方法签名特意只使用 {@code Long} 和 {@code Map} 等 JDK 类型，使网关无需加载
 * 原始业务 API JAR 即可通过 Dubbo 泛化调用执行。返回的 {@code Map} 使用平台标准
 * Envelope 结构：{@code {code, value, message}}。</p>
 */
@CapabilityGroup(idPrefix = "order", protocol = CapabilityProtocol.DUBBO)
public interface OrderQueryApi {

    /**
     * 查询当前组织可见的单个订单。
     *
     * @param orgId 组织标识，由网关从 Principal 注入
     * @param request 业务请求，只允许模型提供 {@code orderNo}
     * @return 平台标准 Envelope；成功时结构为
     * {@code {code: "200", value: {...}, message: "success"}}
     */
    @Capability(
            id = "order.detail.query",
            version = "1.0.0",
            risk = CapabilityRisk.READ_ONLY,
            policyRef = "order.detail.read",
            displayName = "查询订单详情",
            description = "按订单号查询当前组织可见的订单详情")
    @CapInput(schemaResource = "schemas/order-detail-input.json")
    @CapOutput(
            mode = CapabilityOutputMode.ENVELOPE,
            envelopeProfile = "standard-result-v1",
            schemaResource = "schemas/order-detail-public.json",
            projection = {
                    @CapProjection(from = "/orderNo", to = "/orderNo"),
                    @CapProjection(from = "/status", to = "/status"),
                    @CapProjection(from = "/amount", to = "/amount"),
                    @CapProjection(from = "/customerName", to = "/customerName")
            },
            redactions = @CapRedaction(
                    path = "/customerName",
                    method = CapabilityRedactionMethod.PARTIAL_MASK),
            maxBytes = 65536)
    Map<String, Object> query(
            @CapArg(
                    source = CapabilityArgumentSource.PRINCIPAL,
                    sourcePath = "/orgId") Long orgId,
            @CapComposite(@CapFieldBinding(
                    targetPath = "/orderNo",
                    source = CapabilityArgumentSource.MODEL,
                    sourcePath = "/orderNo")) Map<String, Object> request);
}
