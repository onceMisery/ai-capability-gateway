package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.EnvelopeConfig;
import com.ai.gateway.domain.model.EnvelopeProfile;
import com.ai.gateway.domain.port.EnvelopeProfileRegistry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EnvelopeProfileRegistry} 的内存实现。
 *
 * <p>（响应契约）规定网关不能依赖业务统一的响应 Java JAR 包。每种统一响应结构类型都必须注册为
 * 标准信封配置（envelope profile），并在兼容性测试中针对真实的 Provider 进行校验。</p>
 *
 * <p>该实现以平台标准配置（{@code code="200"}、{@code dataPath=/value}、{@code messagePath=/message}）
 * 初始化。额外的配置可通过 {@link #register} 在运行时注册。</p>
 *
 * <p>使用 {@link ConcurrentHashMap} 以保证线程安全访问。该实现适用于单实例部署与测试。若需要
 * 多实例一致性，则需使用基于数据库的实现。</p>
 *
 * @author cmiracle@163.com
 * @see EnvelopeProfileRegistry
 * @since 0.1.0
 */
@Repository
public class JdbcEnvelopeProfileRegistry implements EnvelopeProfileRegistry {

    private static final String PLATFORM_STANDARD = "platform-standard";

    private final ConcurrentHashMap<String, EnvelopeProfile> profiles = new ConcurrentHashMap<>();

    /**
     * 构造一个新的 JdbcEnvelopeProfileRegistry，预加载平台标准信封配置。
     */
    public JdbcEnvelopeProfileRegistry() {
        EnvelopeConfig standardConfig = new EnvelopeConfig(
                "/code",
                List.of("200"),
                "/value",
                "/message");
        EnvelopeProfile standardProfile = new EnvelopeProfile(
                PLATFORM_STANDARD,
                standardConfig,
                "Platform standard unified-response envelope: code=/code (200), data=/value, message=/message");
        profiles.put(PLATFORM_STANDARD, standardProfile);
    }

    @Override
    public Optional<EnvelopeProfile> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(profiles.get(name));
    }

    @Override
    public void register(EnvelopeProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        profiles.put(profile.name(), profile);
    }
}
