/*
 * PayReplayGuard —— 防重放（replay）守卫（Redis 去重 + 本地缓存兜底）。
 *
 * 职责：
 *   - tryMark(key)：SET key "1" NX EX ttl  → 该 (appKey,timestamp,nonce) 是否首次出现
 *       - "OK"   = 首次出现（新请求），标记成功，放行
 *       - null   = key 已存在（重放请求），拦截
 *       - 异常   = Redis 故障，降级到本地缓存兜底
 *
 * 兜底策略（本地缓存）：
 *   - 使用 Guava Cache（ShenYu Bootstrap 自身依赖，无需新增依赖）
 *   - Redis 故障时，本地缓存提供单节点级别的防重放保护
 *   - TTL 与 Redis 一致（330s），Guava 自动清理过期条目
 *   - maximumSize 限制防止 OOM（默认 100k 条目，可配置）
 *
 * 本地缓存局限：
 *   - 分布式多节点场景下，不同节点的本地缓存独立（单节点内防护）
 *   - 节点重启后本地缓存清空（重启后 Redis 应已恢复）
 *   - 这是降级保护，不替代 Redis 的分布式防护能力
 *
 * 依赖：lettuce-core（bootstrap 镜像 lib/ 已自带 6.1.10.RELEASE，本模块 provided）
 *       guava（ShenYu Bootstrap 自身依赖 32.0.0-jre，本模块 provided）
 * 连接懒初始化：首次 tryMark 时建连，避免功能关闭时白建连接。
 */
package org.apache.shenyu.plugin.sign.custom;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 防重放守卫。见 docs/pay-replay-防重放-设计开发方案.md §4/§5。
 */
public class PayReplayGuard implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PayReplayGuard.class);

    /** replay Value（占位，仅用于标记该 nonce 已使用） */
    static final String REPLAY_VALUE = "1";

    /** TTL 随机偏移最大值（秒），防缓存雪崩（大量 key 集中过期） */
    static final int TTL_RANDOM_OFFSET_SECONDS = 30;

    private final PayReplayProperties props;

    /** 懒初始化的 lettuce 客户端与连接（volatile 双检锁） */
    private volatile RedisClient client;

    private volatile StatefulRedisConnection<String, String> connection;

    private final Object connectLock = new Object();

    // ====== 本地缓存兜底（Redis 故障时使用 Guava Cache）======

    /** 本地缓存（Guava Cache，ShenYu Bootstrap 自身依赖） */
    private final Cache<String, Boolean> localCache;

    /**
     * 构造。
     *
     * @param props 配置
     */
    public PayReplayGuard(final PayReplayProperties props) {
        this.props = props;
        // 初始化本地缓存（TTL=330s 与 Redis 一致，maximumSize 防止 OOM）
        // 注意：TTL 在构造时全局配置，put() 时不需要指定
        this.localCache = CacheBuilder.newBuilder()
                .expireAfterWrite(props.getTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(props.getLocalCacheMaxSize())
                .build();
    }

    /**
     * 配置访问器。
     *
     * @return 配置
     */
    public PayReplayProperties getProps() {
        return props;
    }

    /**
     * 标记并尝试放行一个 (appKey,timestamp,nonce) 组合。
     *
     * <p>SET key "1" NX EX ttl：
     *   - 返回 "OK"  → 首次出现，标记成功，返回 {@code true}（放行）
     *   - 返回 null  → key 已存在（重放），返回 {@code false}（拦截）
     *   - Redis 异常 → 降级到本地缓存兜底，返回 {@code true}（放行）或 {@code false}（拦截）
     *
     * <p>本地缓存兜底策略：
     *   - 本地缓存命中 → 拦截（单节点内重放）
     *   - 本地缓存未命中 → 放行并标记（首次请求）
     *
     * @param key replay:{appKey}:{timestamp}:{nonce}
     * @return true=放行；false=拦截
     */
    /**
     * 计算带随机偏移的 TTL（防缓存雪崩）。
     *
     * <p>TTL = Base_TTL(330s) + Random(0~30s)
     * <ul>
     *   <li>目的：业务高峰时大量 key 集中创建，若 TTL 固定会集中过期，导致 Redis 雪崩</li>
     *   <li>随机偏移：将过期时间分散到 30s 窗口，降低 Redis 瞬时负载</li>
     *   <li>安全：延长而非缩短防护窗口（330s~359s），更安全</li>
     * </ul>
     *
     * @return TTL（秒）
     */
    private int calculateRandomTtl() {
        final int base = props.getTtlSeconds();
        // 随机偏移 0~30s，防大量 key 集中过期
        final int offset = ThreadLocalRandom.current().nextInt(TTL_RANDOM_OFFSET_SECONDS);
        return base + offset;
    }

    public boolean tryMark(final String key) {
        if (!props.isEnabled()) {
            return true;
        }
        try {
            final int ttl = calculateRandomTtl();
            final String result = commands().set(key, REPLAY_VALUE,
                    SetArgs.Builder.nx().ex(ttl));
            final boolean marked = "OK".equals(result);
            if (marked) {
                LOG.debug("[GW-Replay] 首次出现（Redis）key={} ttl={}s (base={}s+offset={}s)",
                        key, ttl, props.getTtlSeconds(), ttl - props.getTtlSeconds());
            } else {
                LOG.info("[GW-Replay] 重放拦截（Redis）key={}", key);
            }
            return marked;
        } catch (final Exception ex) {
            // 兜底：本地缓存降级（而非直接放行）
            return tryMarkLocal(key, ex);
        }
    }

    /**
     * 本地缓存兜底（Redis 故障时）。
     *
     * <p>使用 Guava Cache 提供单节点级别的防重放保护。
     * <p>TTL = 330s（与 Redis 基础 TTL 一致，固定值）。
     * <p>Guava 的 TTL 在构造时通过 expireAfterWrite 全局配置，put() 时自动应用。
     *
     * @param key replay:{appKey}:{timestamp}:{nonce}
     * @param redisException Redis 异常
     * @return true=放行；false=拦截
     */
    private boolean tryMarkLocal(final String key, final Exception redisException) {
        if (!props.isLocalCacheEnabled()) {
            LOG.warn("[GW-Replay] Redis 异常（本地缓存兜底关闭，直接放行）key={} err={}",
                    key, redisException.getMessage());
            return true; // 降级关闭时直接放行（与旧行为一致）
        }

        final Boolean exists = localCache.getIfPresent(key);
        if (exists != null) {
            LOG.warn("[GW-Replay] 重放拦截（本地缓存兜底）key={} redisErr={}",
                    key, redisException.getMessage());
            return false; // 本地缓存命中 → 拦截
        }

        // put() 时自动应用构造时配置的 TTL（330s）
        localCache.put(key, Boolean.TRUE);
        LOG.warn("[GW-Replay] 首次标记（本地缓存兜底）key={} redisErr={}",
                key, redisException.getMessage());
        return true; // 本地缓存未命中 → 放行并标记
    }

    // ====== 连接管理 ======

    /**
     * 取同步命令接口（懒初始化连接，双检锁）。
     *
     * <p>protected 便于单测覆盖（mock commands）。
     *
     * @return RedisCommands
     */
    protected RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn == null || !conn.isOpen()) {
            synchronized (connectLock) {
                conn = this.connection;
                if (conn == null || !conn.isOpen()) {
                    if (client == null) {
                        client = RedisClient.create(RedisURI.create(props.getRedisUri()));
                    }
                    conn = client.connect();
                    conn.setTimeout(Duration.ofMillis(props.getRedisTimeoutMs()));
                    this.connection = conn;
                    LOG.info("[GW-Replay] Redis 连接建立 {}", props);
                }
            }
        }
        return conn.sync();
    }

    /**
     * 清理本地缓存（单测用）。
     */
    void clearLocalCache() {
        localCache.invalidateAll();
    }

    /**
     * 获取本地缓存大小（单测用）。
     *
     * @return 本地缓存大小
     */
    long getLocalCacheSize() {
        return localCache.size();
    }

    @Override
    public void close() {
        try {
            if (connection != null) {
                connection.close();
            }
            if (client != null) {
                client.shutdown();
            }
            localCache.invalidateAll();
        } catch (final Exception ex) {
            LOG.warn("[GW-Replay] 关闭 Redis 连接异常：{}", ex.getMessage());
        }
    }
}