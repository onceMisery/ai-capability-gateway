package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.Protocol;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded reference cache for Dubbo GenericService instances.
 *
 * <p>The cache key includes:
 * {@code registryRef + interfaceName + group + version + protocol + serialization}.
 * References use a bounded cache, and after a snapshot switch, references no
 * longer in use are lazily evicted. Creation failure does not pollute the
 * active cache. The manager supports graceful shutdown, waiting for
 * in-flight requests to complete before releasing references.</p>
 *
 * <p>Uses {@link org.apache.dubbo.config.ReferenceConfig} to create and
 * manage Dubbo service references in generic mode.</p>
 *
 * @since 0.1.0
 */
@Component
public class DubboReferenceManager {

    private static final Logger log = LoggerFactory.getLogger(DubboReferenceManager.class);

    /**
     * The default maximum number of cached references.
     */
    private static final int DEFAULT_MAX_CACHE_SIZE = 256;

    /**
     * The default idle timeout for lazy eviction (30 minutes).
     */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * The maximum time to wait for in-flight requests during shutdown.
     */
    private static final long SHUTDOWN_WAIT_TIMEOUT_MS = 30_000L;

    /**
     * A mutable cache entry containing the GenericService, ReferenceConfig,
     * and last access time. The lastAccessTime is volatile to allow
     * lock-free reads with safe updates.
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
     * Constructs a new DubboReferenceManager with default settings.
     *
     * <p>This constructor is used by Spring for component scanning. Registry
     * addresses must be registered via {@link #registerRegistryAddress} before
     * any {@code getOrCreate} calls.</p>
     */
    public DubboReferenceManager() {
        this(new ConcurrentHashMap<>(), DEFAULT_MAX_CACHE_SIZE, DEFAULT_IDLE_TIMEOUT_MS);
    }

    /**
     * Constructs a new DubboReferenceManager with the specified registry
     * address map and cache configuration.
     *
     * @param registryAddresses a map of registryRef to registry address
     * (e.g., "nacos://127.0.0.1:8848")
     * @param maxCacheSize the maximum number of cached references
     * @param idleTimeoutMs the idle timeout for lazy eviction in milliseconds
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
     * Registers a registry address for a given registryRef.
     *
     * @param registryRef the registry reference name
     * @param registryAddress the registry address (e.g., "nacos://127.0.0.1:8848")
     */
    public void registerRegistryAddress(String registryRef, String registryAddress) {
        Objects.requireNonNull(registryRef, "registryRef must not be null");
        Objects.requireNonNull(registryAddress, "registryAddress must not be null");
        registryAddresses.put(registryRef, registryAddress);
        log.info("Registered registry address: ref={}, address={}", registryRef, registryAddress);
    }

    /**
     * Gets or creates a GenericService for the given Dubbo service coordinates.
     *
     * <p>The cache key includes:
     * {@code registryRef + interfaceName + group + version + protocol + serialization}.
     * If a cached reference exists, it is returned. Otherwise, a new
     * {@link ReferenceConfig} is created and the GenericService is obtained.</p>
     *
     * <p>Creation failure does not pollute the active cache: if
     * {@code ReferenceConfig.get()} throws an exception, no entry is added
     * to the cache, and subsequent calls will attempt creation again.</p>
     *
     * @param registryRef the operationally pre-configured registry reference
     * @param interfaceName the fully-qualified service interface name
     * @param group the service group
     * @param version the service version
     * @param serialization the serialization method (must be whitelisted)
     * @return the GenericService for the given coordinates
     * @throws IllegalStateException if the manager has been shut down or the
     * registry address is not configured
     * @throws RuntimeException if GenericService creation fails
     */
    public GenericService getOrCreate(String registryRef,
                                      String interfaceName,
                                      String group,
                                      String version,
                                      String serialization) {
        if (shutdownRequested.get()) {
            throw new IllegalStateException("DubboReferenceManager has been shut down");
        }

        // Validate serialization whitelist
        SerializationWhitelist.validate(serialization);

        String cacheKey = buildCacheKey(registryRef, interfaceName, group, version, serialization);

        // Try to get from cache first (fast path)
        CacheEntry existing = cache.get(cacheKey);
        if (existing != null) {
            existing.touch();
            return existing.genericService;
        }

        // Create new reference outside the cache — creation failure must not
        // pollute the active cache
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
            // Creation failure — destroy the config and propagate.
            // Do NOT add to cache.
            destroyReferenceConfig(referenceConfig);
            throw new RuntimeException(
                    "Failed to create GenericService for interface=" + interfaceName
                            + ", group=" + group + ", version=" + version, e);
        }

        // Put into cache with size bound (double-checked locking)
        synchronized (this) {
            // Double-check after acquiring lock
            existing = cache.get(cacheKey);
            if (existing != null) {
                // Another thread already created it; discard our new one
                destroyReferenceConfig(referenceConfig);
                existing.touch();
                return existing.genericService;
            }

            // Evict idle entries if at capacity
            evictIdleEntries();

            // Evict oldest entry if still at capacity
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
     * Performs lazy eviction of unused references that have exceeded the
     * idle timeout.
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
     * Evicts the oldest cache entry when the cache is at capacity.
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
     * Creates a new ReferenceConfig for the given service coordinates.
     *
     * @param registryRef the registry reference name
     * @param interfaceName the fully-qualified service interface name
     * @param group the service group
     * @param version the service version
     * @param serialization the serialization method
     * @return a new ReferenceConfig configured for generic invocation
     * @throws IllegalStateException if the registry address is not configured
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
        // Serialization is set per-reference through Dubbo URL parameters
        //
        referenceConfig.setParameters(java.util.Map.of("serialization", serialization));
        referenceConfig.setRegistries(List.of(registryConfig));
        referenceConfig.setCheck(false);
        return referenceConfig;
    }

    /**
     * Builds the cache key from the Dubbo service coordinates.
     *
     * <p>Key format: {@code registryRef|interfaceName|group|version|DUBBO|serialization}</p>
     *
     * @param registryRef the registry reference
     * @param interfaceName the interface name
     * @param group the service group
     * @param version the service version
     * @param serialization the serialization method
     * @return the cache key string
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
     * Destroys a ReferenceConfig, releasing its resources.
     *
     * @param referenceConfig the reference config to destroy; may be null
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
     * Increments the in-flight request counter.
     *
     * @return the new count
     */
    int incrementInFlight() {
        return inFlightRequests.incrementAndGet();
    }

    /**
     * Decrements the in-flight request counter.
     *
     * @return the new count
     */
    int decrementInFlight() {
        return inFlightRequests.decrementAndGet();
    }

    /**
     * Gracefully shuts down the reference manager.
     *
     * <p>Waits for in-flight requests to reach zero (up to the shutdown
     * timeout), then destroys all cached ReferenceConfigs and clears the
     * cache.</p>
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down DubboReferenceManager...");
        shutdownRequested.set(true);

        // Wait for in-flight requests to complete
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

        // Destroy all cached references
        int destroyed = 0;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            destroyReferenceConfig(entry.getValue().referenceConfig);
            destroyed++;
        }
        cache.clear();
        log.info("DubboReferenceManager shutdown complete: destroyed {} references", destroyed);
    }

    /**
     * Returns the current number of cached references.
     *
     * @return the cache size
     */
    public int cacheSize() {
        return cache.size();
    }
}
