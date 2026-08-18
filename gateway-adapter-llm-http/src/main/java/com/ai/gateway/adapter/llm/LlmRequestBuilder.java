package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.port.LlmRouterPort.LlmCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 为 LLM 路由请求构建受限的候选上下文。
 *
 * <p>模型仅接收已授权的 Top-K 候选，每个候选包含一个简短别名和公开描述。请求上下文
 * 不得包含协议绑定、服务地址、接口类名、租户标识、序列化方式、超时或重试配置。</p>
 *
 * <p>该构建器生成一个适用于 LLM API 请求体的 JSON 字符串。候选上下文包含：</p>
 * <ul>
 * <li>简短别名（例如 {@code cap_7k3m2v6p4a9d1f8q}）</li>
 * <li>公开描述：displayName、description、正面/负面示例、同义词</li>
 * <li>公开输入 Schema（仅包含 MODEL 来源的业务字段）</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class LlmRequestBuilder {

    private final ObjectMapper objectMapper;

    /**
     * 使用默认的 ObjectMapper 构造一个新的 LlmRequestBuilder。
     */
    public LlmRequestBuilder() {
        this(new ObjectMapper());
    }

    /**
     * 使用自定义的 ObjectMapper 构造一个新的 LlmRequestBuilder。
     *
     * @param objectMapper JSON 序列化器
     */
    public LlmRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    /**
     * 将 LLM API 请求体构建为 JSON 字符串。
     *
     * <p>请求仅包含受限的候选上下文：简短别名、公开描述（displayName、description、
     * 示例、同义词）以及公开输入 Schema。它不包含协议绑定、服务地址、接口类名、
     * 租户标识、序列化方式、超时或重试配置。</p>
     *
     * @param userText 用户的自然语言请求文本
     * @param candidates 已授权的 Top-K 候选能力
     * @return 用于 LLM API 请求体的 JSON 字符串
     * @throws java.lang.NullPointerException 如果 userText 或 candidates 为 null
     */
    public String buildRequest(String userText, List<LlmCandidate> candidates) {
        Objects.requireNonNull(userText, "userText must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        try {
            ObjectNode rootNode = objectMapper.createObjectNode();

            // 系统提示词约束 LLM 只能从提供的候选中选择
            rootNode.put("system",
                    "You are a capability routing assistant. Select exactly one candidate "
                            + "from the provided list and generate arguments conforming to "
                            + "that candidate's inputSchema. Return one of: SELECT, CLARIFY, "
                            + "or NO_MATCH. You may ONLY select from the provided aliases.");

            // 用户文本
            rootNode.put("userText", userText);

            // 受限候选上下文
            ArrayNode candidatesArray = rootNode.putArray("candidates");
            for (LlmCandidate candidate : candidates) {
                ObjectNode candidateNode = candidatesArray.addObject();
                // 简短别名——不暴露真实的 capabilityId
                candidateNode.put("alias", candidate.alias());
                // 仅公开描述
                candidateNode.put("displayName", candidate.displayName());
                candidateNode.put("description", candidate.description());

                // 正面示例
                if (candidate.positiveExamples() != null) {
                    ArrayNode posArray = candidateNode.putArray("positiveExamples");
                    for (String ex : candidate.positiveExamples()) {
                        posArray.add(ex);
                    }
                }

                // 负面示例
                if (candidate.negativeExamples() != null) {
                    ArrayNode negArray = candidateNode.putArray("negativeExamples");
                    for (String ex : candidate.negativeExamples()) {
                        negArray.add(ex);
                    }
                }

                // 同义词
                if (candidate.synonyms() != null) {
                    ArrayNode synArray = candidateNode.putArray("synonyms");
                    for (String syn : candidate.synonyms()) {
                        synArray.add(syn);
                    }
                }

                // 公开输入 Schema（仅 MODEL 字段）——以原始 JSON 形式嵌入
                if (candidate.inputSchema() != null && !candidate.inputSchema().isEmpty()) {
                    candidateNode.set("inputSchema",
                            objectMapper.valueToTree(candidate.inputSchema()));
                }

                // 刻意不包含：协议绑定、服务地址、接口类名、租户、用户标识、
                // 序列化方式、超时、重试
            }

            String json = objectMapper.writeValueAsString(rootNode);
            log.debug("Built LLM request with {} candidates", candidates.size());
            return json;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build LLM request body", e);
        }
    }
}
