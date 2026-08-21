package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.llm.LlmRequestBuilder;
import com.ai.gateway.adapter.llm.LlmResponseParser;
import com.ai.gateway.adapter.llm.PromptTemplateRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * LLM HTTP 适配器装配（gateway-adapter-llm-http）。
 *
 * <p>聚合 LLM 结构化调用链路的无状态组件：请求构造、响应解析与提示词
 * 模板注册表。注意 {@code HttpLlmRouterAdapter} 需要配置值（endpoint、
 * apiKey、model），无法由 Spring 自动装配，由 {@link BeanConfig} 手工创建。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@Import({
        LlmRequestBuilder.class,
        LlmResponseParser.class,
        PromptTemplateRegistry.class,
})
public class LlmAdaptersConfiguration {
}
