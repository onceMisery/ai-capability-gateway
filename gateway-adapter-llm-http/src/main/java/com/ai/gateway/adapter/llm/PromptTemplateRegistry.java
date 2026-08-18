package com.ai.gateway.adapter.llm;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * 经过版本化管理的提示词模板注册表。
 *
 * <p>提示词模板、模型 ID、temperature 以及解析器版本都必须经过版本化并纳入评估。
 * 该注册表维护一个从模板名称到模板内容的内存映射，并在初始化时预加载一条默认
 * 系统提示词，将 LLM 约束为只能从提供的候选中进行选择。</p>
 *
 * <p>默认系统提示词用于强制实施安全边界：模型只能选择一个候选别名，并为该候选的
 * 公开输入 Schema 生成参数。模型不得输出协议绑定、服务地址、接口类名、租户标识、
 * 序列化方式、超时或重试配置。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class PromptTemplateRegistry {

    /**
     * 默认系统提示词，约束 LLM 只能从提供的候选中选择，并返回三种决策类型之一。
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a capability routing assistant for an AI capability gateway.

            You will receive a list of candidate capabilities, each identified by a
            short alias (e.g., cap_7k3m2v6p4a9d1f8q). Each candidate includes:
            - alias: the short identifier you must use in your response
            - displayName: the user-facing capability name
            - description: the single business action this capability performs
            - positiveExamples: queries this capability handles
            - negativeExamples: queries this capability does NOT handle
            - synonyms: key noun synonyms
            - inputSchema: the public JSON Schema (MODEL fields only) for arguments

            You MUST respond with exactly one of three decision types:

            1. SELECT: Choose the alias that best matches the user's request and
               generate arguments conforming to that candidate's inputSchema.
               Response format:
               {"decision":"SELECT","alias":"<alias>","arguments":{...}}

            2. CLARIFY: If the user's request is ambiguous or missing required
               information, ask a clarifying question.
               Response format:
               {"decision":"CLARIFY","question":"<question>"}

            3. NO_MATCH: If no candidate matches the user's request.
               Response format:
               {"decision":"NO_MATCH","reasonCode":"<reason>"}

            Constraints:
            - You may ONLY select from the provided candidate aliases.
            - You must NOT output protocol bindings, service addresses, interface
              class names, tenant identity, serialization methods, timeout, or
              retry configuration.
            - You must NOT declare authorization success or execution success.
            - Arguments must conform to the selected candidate's inputSchema.
            - Return valid JSON only.
            """;

    private static final String DEFAULT_TEMPLATE_NAME = "default-system";

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    /**
     * 构造一个新的 PromptTemplateRegistry，并预加载默认系统提示词。
     */
    public PromptTemplateRegistry() {
        registerTemplate(DEFAULT_TEMPLATE_NAME, DEFAULT_SYSTEM_PROMPT);
        log.info("PromptTemplateRegistry initialized with default system prompt");
    }

    /**
     * 按名称检索提示词模板。
     *
     * @param name 模板名称
     * @return 模板内容；如果未找到则为 {@code null}
     * @throws java.lang.NullPointerException 如果 name 为 null
     */
    public String getTemplate(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return templates.get(name);
    }

    /**
     * 注册或替换一条提示词模板。
     *
     * <p>模板在评估流程中经过版本化管理。在运行时替换模板不会影响已经发出去的
     * LLM 请求。</p>
     *
     * @param name 模板名称
     * @param content 模板内容
     * @throws java.lang.NullPointerException 如果 name 或 content 为 null
     */
    public void registerTemplate(String name, String content) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(content, "content must not be null");
        templates.put(name, content);
        log.debug("Registered prompt template: name={}", name);
    }
}
