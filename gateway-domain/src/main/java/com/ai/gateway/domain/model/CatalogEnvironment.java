package com.ai.gateway.domain.model;

/**
 * 能力目录的唯一逻辑环境。
 *
 * <p>部署环境由 Nacos 和协议注册中心负责隔离，能力目录本身只维护一份
 * 全局快照。保留固定标识是为了兼容现有数据库字段和端口契约。</p>
 */
public final class CatalogEnvironment {

    public static final String DEFAULT = "default";

    private CatalogEnvironment() {
    }
}
