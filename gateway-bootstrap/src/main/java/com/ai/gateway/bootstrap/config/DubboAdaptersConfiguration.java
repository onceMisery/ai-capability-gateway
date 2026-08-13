package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.dubbo.DubboAttachmentManager;
import com.ai.gateway.adapter.dubbo.DubboInvocationAdapter;
import com.ai.gateway.adapter.dubbo.DubboReferenceManager;
import com.ai.gateway.adapter.dubbo.GenericArgumentBuilder;
import com.ai.gateway.adapter.dubbo.GenericResultStripper;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Dubbo 适配器装配（gateway-adapter-dubbo）。
 *
 * <p>聚合 Dubbo 泛化调用链路的全部适配器：引用管理、泛化参数构造、
 * 结果剥离、附件透传与调用适配器本身。</p>
 *
 * @since 0.1.0
 */
@Configuration
@Import({
        DubboReferenceManager.class,
        GenericArgumentBuilder.class,
        GenericResultStripper.class,
        DubboAttachmentManager.class,
        DubboInvocationAdapter.class,
})
public class DubboAdaptersConfiguration {
}
