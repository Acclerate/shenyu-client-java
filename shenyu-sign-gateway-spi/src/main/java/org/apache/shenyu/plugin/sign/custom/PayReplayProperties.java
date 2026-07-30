/*
 * PayReplayProperties —— 防重放（replay）Redis 存储配置（环境变量源）。
 *
 * 配置入口 = docker-compose-ShenYu-local.yaml → shenyu-bootstrap.environment。
 * 全部有默认值；ENABLED=false 或 REDIS_URI 为空时功能整体关闭，网关行为与旧版完全一致。
 *
 * 防重放（replay）保护维度：同一 timestamp+nonce 的「重放」拦截（TTL 5min+30s）。
 * 注：IN-FLIGHT（并发幂等）已迁移至支付服务入口（Controller/Service 层），网关侧不再实现。
 *
 * 兜底策略：Redis 故障时使用本地缓存（Guava Cache，ShenYu Bootstrap 自身依赖）
 * 提供单节点级别的防重放保护，避免完全放行。
 */
package org.apache.shenyu.plugin.sign.custom;

/**
 * 防重放配置。见 docs/pay-replay-防重放-设计开发方案.md。
 */
public final class PayReplayProperties {

    /** 总开关 */
    private final boolean enabled;

    /** Redis URI，如 redis://:123456@host.docker.internal:6379/0 */
    private final String redisUri;

    /** replay key 基础 TTL（秒）= 5min + 30s = 330s */
    private final int ttlSeconds;

    /** Redis 命令超时（毫秒） */
    private final long redisTimeoutMs;

    /** 本地缓存兜底开关（Redis 故障时使用 Guava Cache） */
    private final boolean localCacheEnabled;

    /** 本地缓存最大条目数（防 OOM） */
    private final long localCacheMaxSize;

    private PayReplayProperties(final boolean enabled, final String redisUri, final int ttlSeconds,
                                final long redisTimeoutMs, final boolean localCacheEnabled,
                                final long localCacheMaxSize) {
        this.enabled = enabled;
        this.redisUri = redisUri;
        this.ttlSeconds = ttlSeconds;
        this.redisTimeoutMs = redisTimeoutMs;
        this.localCacheEnabled = localCacheEnabled;
        this.localCacheMaxSize = localCacheMaxSize;
    }

    /**
     * 从环境变量构造（全部有默认值）。
     *
     * <p>REDIS_URI 取 {@code PAY_REPLAY_REDIS_URI}；为空则视为未配置（功能关闭，fail-open 放行）。
     *
     * <p>本地缓存兜底：Redis 故障时使用 Guava Cache（ShenYu Bootstrap 自身依赖）
     * 提供单节点级别的防重放保护。开关默认开启，可通过 PAY_REPLAY_LOCAL_CACHE_ENABLED=false 关闭。
     *
     * @return 配置实例
     */
    public static PayReplayProperties fromEnv() {
        final boolean enabled = Boolean.parseBoolean(env("PAY_REPLAY_ENABLED", "false"));
        final String uri = env("PAY_REPLAY_REDIS_URI", "");
        return new PayReplayProperties(
                enabled && !uri.trim().isEmpty(),
                uri.trim(),
                envInt("PAY_REPLAY_TTL_SECONDS", 300),
                envLong("PAY_REPLAY_REDIS_TIMEOUT_MS", 500L),
                Boolean.parseBoolean(env("PAY_REPLAY_LOCAL_CACHE_ENABLED", "true")),
                envLong("PAY_REPLAY_LOCAL_CACHE_MAX_SIZE", 100_000L));
    }

    /**
     * 测试/编程用工厂。
     *
     * @param enabled 开关
     * @param redisUri redis uri
     * @param ttlSeconds ttl 秒
     * @param redisTimeoutMs 命令超时
     * @param localCacheEnabled 本地缓存开关
     * @param localCacheMaxSize 本地缓存最大条目
     * @return 配置实例
     */
    public static PayReplayProperties of(final boolean enabled, final String redisUri, final int ttlSeconds,
                                         final long redisTimeoutMs, final boolean localCacheEnabled,
                                         final long localCacheMaxSize) {
        return new PayReplayProperties(enabled, redisUri, ttlSeconds, redisTimeoutMs,
                localCacheEnabled, localCacheMaxSize);
    }

    private static String env(final String key, final String def) {
        final String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static int envInt(final String key, final int def) {
        try {
            return Integer.parseInt(env(key, String.valueOf(def)));
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    private static long envLong(final String key, final long def) {
        try {
            return Long.parseLong(env(key, String.valueOf(def)));
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getRedisUri() {
        return redisUri;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public long getRedisTimeoutMs() {
        return redisTimeoutMs;
    }

    public boolean isLocalCacheEnabled() {
        return localCacheEnabled;
    }

    public long getLocalCacheMaxSize() {
        return localCacheMaxSize;
    }

    @Override
    public String toString() {
        // 打印时脱敏密码
        final String safeUri = redisUri.replaceAll("//(.*):(.*)@", "//$1:***@");
        return "PayReplayProperties{enabled=" + enabled + ", redisUri='" + safeUri + '\''
                + ", ttlSeconds=" + ttlSeconds
                + ", redisTimeoutMs=" + redisTimeoutMs
                + ", localCacheEnabled=" + localCacheEnabled
                + ", localCacheMaxSize=" + localCacheMaxSize + '}';
    }
}