package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.Protocol;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.service.GenericService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dubbo GenericService 实例的有界引用缓存。
 *
 * <p>缓存键包括：
 * {@code registryRef + interfaceName + group + version + protocol + serialization}。
 * 引用使用有界缓存，快照切换后不再使用的引用会被惰性淘汰。创建失败不会污染
 * 活跃缓存。管理器支持优雅关闭，在释放引用前会等待在途请求完成。</p>
 *
 * <p>使用 {@link org.apache.dubbo.config.ReferenceConfig} 以泛化模式创建和管理
 * Dubbo 服务引用。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
@Component
public class DubboReferenceManager {

    /**
     * 缓存引用的默认最大数量。
     */
    private static final int DEFAULT_MAX_CACHE_SIZE = 256;

    /**
     * 惰性淘汰的默认空闲超时时间（30 分钟）。
     */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 关闭期间等待在途请求的最大时长。
     */
    private static final long SHUTDOWN_WAIT_TIMEOUT_MS = 30_000L;

    /**
     * 可变的缓存条目，包含 GenericService、ReferenceConfig 和最后访问时间。
     * lastAccessTime 使用 volatile 修饰，以在无锁读取的同时保证更新的安全性。
     */
    private static final class CacheEntry {
        final GenericService genericService;
        final ReferenceConfig<GenericService> referenceConfig;
        volatile long lastAccessTime;

        CacheEntry(GenericService genericService,
                   ReferenceConfig<GenericService> referenceConfig,
                   long lastAccessTime) {
            this.genericService = genericService;
            this.referenceConfig = referenceConfig;
            this.lastAccessTime = lastAccessTime;
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, String> registryAddresses;
    private final int maxCacheSize;
    private final long idleTimeoutMs;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * 使用默认设置构造一个新的 DubboReferenceManager。
     *
     * <p>该构造函数供 Spring 组件扫描使用。在任何 {@code getOrCreate} 调用之前，
     * 必须先通过 {@link #registerRegistryAddress} 注册注册中心地址。</p>
     */
    public DubboReferenceManager() {
        this(new ConcurrentHashMap<>(), DEFAULT_MAX_CACHE_SIZE, DEFAULT_IDLE_TIMEOUT_MS);
    }

    /**
     * 使用指定的注册中心地址映射和缓存配置构造一个新的 DubboReferenceManager。
     *
     * @param registryAddresses registryRef 到注册中心地址的映射
     * （例如 "nacos://127.0.0.1:8848"）
     * @param maxCacheSize 缓存引用的最大数量
     * @param idleTimeoutMs 惰性淘汰的空闲超时时间（毫秒）
     */
    public DubboReferenceManager(Map<String, String> registryAddresses,
                                 int maxCacheSize,
                                 long idleTimeoutMs) {
        this.registryAddresses = new ConcurrentHashMap<>(
                Objects.requireNonNull(registryAddresses,
                        "registryAddresses must not be null"));
        this.maxCacheSize = maxCacheSize;
        this.idleTimeoutMs = idleTimeoutMs;
        log.info("DubboReferenceManager initialized: maxCacheSize={}, idleTimeoutMs={}",
                maxCacheSize, idleTimeoutMs);
    }

    /**
     * 为给定的 registryRef 注册注册中心地址。
     *
     * @param registryRef 注册中心引用名称
     * @param registryAddress 注册中心地址（例如 "nacos://127.0.0.1:8848"）
     */
    public void registerRegistryAddress(String registryRef, String registryAddress) {
        Objects.requireNonNull(registryRef, "registryRef must not be null");
        Objects.requireNonNull(registryAddress, "registryAddress must not be null");
        registryAddresses.put(registryRef, registryAddress);
        log.info("Registered registry address: ref={}, address={}", registryRef, registryAddress);
    }

    /**
     * 获取或创建指定 Dubbo 服务坐标对应的 GenericService。
     *
     * <p>缓存键包括：
     * {@code registryRef + interfaceName + group + version + protocol + serialization}。
     * 如果缓存中已有引用，则直接返回；否则创建新的 {@link ReferenceConfig} 并获取
     * GenericService。</p>
     *
     * <p>创建失败不会污染活跃缓存：如果 {@code ReferenceConfig.get()} 抛出异常，
     * 不会向缓存添加任何条目，后续调用将再次尝试创建。</p>
     *
     * @param registryRef 运营上预配置的注册中心引用
     * @param interfaceName 服务接口的全限定名
     * @param group 服务分组
     * @param version 服务版本
     * @param serialization 序列化方式（必须在白名单内）
     * @return 指定坐标对应的 GenericService
     * @throws IllegalStateException 如果管理器已关闭或未配置注册中心地址
     * @throws RuntimeException 如果 GenericService 创建失败
     */
    public GenericService getOrCreate(String registryRef,
                                      String interfaceName,
                                      String group,
                                      String version,
                                      String serialization) {
        if (shutdownRequested.get()) {
            throw new IllegalStateException("DubboReferenceManager has been shut down");
        }

        // 校验序列化白名单
        SerializationWhitelist.validate(serialization);

        String cacheKey = buildCacheKey(registryRef, interfaceName, group, version, serialization);

        // 首先尝试从缓存获取（快速路径）
        CacheEntry existing = cache.get(cacheKey);
        if (existing != null) {
            existing.touch();
            return existing.genericService;
        }

        // 在缓存之外创建新引用——创建失败不得污染活跃缓存
        ReferenceConfig<GenericService> referenceConfig = createReferenceConfig(
                registryRef, interfaceName, group, version, serialization);
        GenericService newService;
        try {
            newService = referenceConfig.get();
            if (newService == null) {
                throw new RuntimeException(
                        "ReferenceConfig.get() returned null for interface: " + interfaceName);
            }
        } catch (Exception e) {
            // 创建失败——销毁配置并向上抛出。
            // 不向缓存中添加。
            destroyReferenceConfig(referenceConfig);
            throw new RuntimeException(
                    "Failed to create GenericService for interface=" + interfaceName
                            + ", group=" + group + ", version=" + version, e);
        }

        // 以大小上限的方式放入缓存（双重检查锁定）
        synchronized (this) {
            // 获取锁后再次检查
            existing = cache.get(cacheKey);
            if (existing != null) {
                // 其他线程已创建；丢弃我们新建的这个
                destroyReferenceConfig(referenceConfig);
                existing.touch();
                return existing.genericService;
            }

            // 如果达到容量上限，淘汰空闲条目
            evictIdleEntries();

            // 如果仍达到容量上限，淘汰最旧的条目
            if (cache.size() >= maxCacheSize) {
                evictOldestEntry();
            }

            CacheEntry entry = new CacheEntry(newService, referenceConfig, System.currentTimeMillis());
            cache.put(cacheKey, entry);
            log.info("Created and cached GenericService: key={}, interface={}",
                    cacheKey, interfaceName);
            return newService;
        }
    }

    /**
     * 对超过空闲超时时间且不再使用的引用执行惰性淘汰。
     */
    private void evictIdleEntries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            CacheEntry cached = entry.getValue();
            if (now - cached.lastAccessTime > idleTimeoutMs) {
                cache.remove(entry.getKey(), cached);
                destroyReferenceConfig(cached.referenceConfig);
                log.debug("Evicted idle reference: key={}", entry.getKey());
            }
        }
    }

    /**
     * 当缓存达到容量上限时淘汰最旧的缓存条目。
     */
    private void evictOldestEntry() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            long accessTime = entry.getValue().lastAccessTime;
            if (accessTime < oldestTime) {
                oldestTime = accessTime;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            CacheEntry removed = cache.remove(oldestKey);
            if (removed != null) {
                destroyReferenceConfig(removed.referenceConfig);
                log.debug("Evicted oldest reference to make room: key={}", oldestKey);
            }
        }
    }

    /**
     * 为指定的服务坐标创建新的 ReferenceConfig。
     *
     * @param registryRef 注册中心引用名称
     * @param interfaceName 服务接口的全限定名
     * @param group 服务分组
     * @param version 服务版本
     * @param serialization 序列化方式
     * @return 配置为泛化调用的新 ReferenceConfig
     * @throws IllegalStateException 如果未配置注册中心地址
     */
    private ReferenceConfig<GenericService> createReferenceConfig(String registryRef,
                                                                   String interfaceName,
                                                                   String group,
                                                                   String version,
                                                                   String serialization) {
        String registryAddress = registryAddresses.get(registryRef);
        if (registryAddress == null) {
            throw new IllegalStateException(
                    "Registry address not configured for registryRef: " + registryRef
                            + "");
        }

        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress(registryAddress);

        ReferenceConfig<GenericService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setInterface(interfaceName);
        referenceConfig.setGroup(group);
        referenceConfig.setVersion(version);
        referenceConfig.setGeneric("true");
        // 序列化方式通过 Dubbo URL 参数按引用设置
        //
        referenceConfig.setParameters(java.util.Map.of("serialization", serialization));
        referenceConfig.setRegistries(List.of(registryConfig));
        referenceConfig.setCheck(false);
        return referenceConfig;
    }

    /**
     * 根据 Dubbo 服务坐标构建缓存键。
     *
     * <p>键格式：{@code registryRef|interfaceName|group|version|DUBBO|serialization}</p>
     *
     * @param registryRef 注册中心引用
     * @param interfaceName 接口名称
     * @param group 服务分组
     * @param version 服务版本
     * @param serialization 序列化方式
     * @return 缓存键字符串
     */
    private String buildCacheKey(String registryRef,
                                 String interfaceName,
                                 String group,
                                 String version,
                                 String serialization) {
        return registryRef + "|" + interfaceName + "|"
                + (group != null ? group : "") + "|"
                + (version != null ? version : "") + "|"
                + Protocol.DUBBO.name() + "|"
                + serialization;
    }

    /**
     * 销毁 ReferenceConfig，释放其资源。
     *
     * @param referenceConfig 待销毁的引用配置；可以为 null
     */
    private void destroyReferenceConfig(ReferenceConfig<?> referenceConfig) {
        if (referenceConfig != null) {
            try {
                referenceConfig.destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy ReferenceConfig: {}", e.getMessage());
            }
        }
    }

    /**
     * 递增在途请求计数器。
     *
     * @return 新的计数值
     */
    int incrementInFlight() {
        return inFlightRequests.incrementAndGet();
    }

    /**
     * 递减在途请求计数器。
     *
     * @return 新的计数值
     */
    int decrementInFlight() {
        return inFlightRequests.decrementAndGet();
    }

    /**
     * 优雅地关闭引用管理器。
     *
     * <p>等待在途请求数归零（最多等待至关闭超时时间），然后销毁所有缓存的
     * ReferenceConfig 并清空缓存。</p>
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down DubboReferenceManager...");
        shutdownRequested.set(true);

        // 等待在途请求完成
        long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_TIMEOUT_MS;
        while (inFlightRequests.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Shutdown timeout reached with {} in-flight requests, "
                                + "proceeding with cache cleanup",
                        inFlightRequests.get());
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown interrupted with {} in-flight requests",
                        inFlightRequests.get());
                break;
            }
        }

        // 销毁所有缓存的引用
        int destroyed = 0;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            destroyReferenceConfig(entry.getValue().referenceConfig);
            destroyed++;
        }
        cache.clear();
        log.info("DubboReferenceManager shutdown complete: destroyed {} references", destroyed);
    }

    /**
     * 返回当前缓存的引用数量。
     *
     * @return 缓存大小
     */
    public int cacheSize() {
        return cache.size();
    }
}
