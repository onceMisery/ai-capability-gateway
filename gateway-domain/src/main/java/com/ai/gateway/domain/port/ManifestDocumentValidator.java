package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.ValidationReport;

/**
 * 原始 Manifest 文档结构校验端口。
 *
 * <p>入参必须是仅由 Map、List 和 JSON 标量组成的通用数据树。领域层只声明
 * 校验能力，不依赖 Jackson 或具体 JSON Schema 实现。</p>
 */
public interface ManifestDocumentValidator {

    /**
     * 使用平台唯一的 Capability Manifest Schema 校验原始文档。
     *
     * @param document JSON 兼容的数据树
     * @return 校验报告；错误列表为空时表示通过
     */
    ValidationReport validate(Object document);
}
