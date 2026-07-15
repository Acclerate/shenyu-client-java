package org.apache.shenyu.plugin.sign.custom;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 动态业务公钥提供器（多租户）：支持 classpath 与 Redis 两种源，per-appKey 本地缓存防击穿。
 *
 * <p>Redis key 格式由 {@code redisKeyPattern} 决定，如 {@code shenyu:sign:%s:biz-public-key.pem}，
 * 其中 {@code %s} 会被替换为请求头 {@code X-Pay-App-Key} 的值（如 {@code biz001}）。
 *
 * <p>当 Redis 不可用时降级到 classpath PEM（所有 appKey 共用同一个兜底公钥）。
 */
public final class DynamicBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicBizPublicKeyProvider.class);

    private final String source;

    private final String classpathLocation;

    private final String redisKeyPattern;

    private final long cacheTtlMillis;

    private final long failureRetryMillis;

    private final boolean allowStaleOnRefreshFailure;

    /** Per-appKey 缓存 */
    private final ConcurrentHashMap<String, CachedEntry> cacheMap = new ConcurrentHashMap<>();

    /** Per-appKey 锁（防止同一 appKey 并发刷新） */
    private final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();

    private final RedisClient redisClient;

    private StatefulRedisConnection<String, String> redisConnection;

    private volatile boolean redisAvailable = true;
    private volatile long lastRedisFailAtMillis = 0L;
    private volatile String lastError = null;
    private volatile String currentSource = "classpath";
    private ScheduledExecutorService reconnectTimer;

    DynamicBizPublicKeyProvider(
            final String source,
            final String classpathLocation,
            final String redisHost,
            final int redisPort,
            final String redisPassword,
            final int redisDatabase,
            final long redisTimeoutMillis,
            final String redisKeyPattern,
            final long cacheTtlSeconds,
            final long failureRetrySeconds,
            final boolean allowStaleOnRefreshFailure
    ) {
        this.source = source == null ? "classpath" : source.trim().toLowerCase();
        this.classpathLocation = classpathLocation;
        this.redisKeyPattern = redisKeyPattern;
        this.cacheTtlMillis = Math.max(1L, cacheTtlSeconds) * 1000L;
        this.failureRetryMillis = Math.max(1L, failureRetrySeconds) * 1000L;
        this.allowStaleOnRefreshFailure = allowStaleOnRefreshFailure;

        if ("redis".equals(this.source)) {
            RedisURI.Builder redisBuilder = RedisURI.builder()
                    .withHost(redisHost)
                    .withPort(redisPort)
                    .withDatabase(redisDatabase)
                    .withTimeout(Duration.ofMillis(Math.max(100L, redisTimeoutMillis)));
            if (StringUtils.hasText(redisPassword)) {
                redisBuilder.withPassword(redisPassword.toCharArray());
            }
            RedisURI redisUri = redisBuilder.build();
            this.redisClient = RedisClient.create(redisUri);
            try {
                this.redisConnection = redisClient.connect();
                this.redisAvailable = true;
                this.currentSource = "redis";
                LOG.info("[GW-Sign] 公钥源已启用 Redis 多租户模式 redis={} db={} pattern={}",
                        redisHost + ":" + redisPort, redisDatabase, redisKeyPattern);
            } catch (Exception e) {
                this.redisAvailable = false;
                this.currentSource = "classpath";
                this.lastError = e.getMessage();
                LOG.warn("[GW-Sign] Redis 连接失败，已降级到 classpath：{}。后台每 60s 尝试重连", e.getMessage());
            }
        } else {
            this.redisClient = null;
            this.redisConnection = null;
            this.currentSource = "classpath";
            LOG.info("[GW-Sign] 公钥源使用 classpath 模式 path={}", classpathLocation);
        }

        if ("redis".equals(this.source)) {
            this.reconnectTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gw-sign-redis-reconnect");
                t.setDaemon(true);
                return t;
            });
            this.reconnectTimer.scheduleWithFixedDelay(this::tryReconnect, 60, 60, TimeUnit.SECONDS);
        } else {
            this.reconnectTimer = null;
        }
    }

    private void tryReconnect() {
        if (redisAvailable || redisClient == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRedisFailAtMillis < 60_000) {
            return;
        }
        try {
            StatefulRedisConnection<String, String> conn = redisClient.connect();
            this.redisConnection = conn;
            this.redisAvailable = true;
            this.currentSource = "redis";
            this.cacheMap.clear();
            LOG.info("[GW-Sign] Redis 已恢复，切回 Redis 源，已清空所有 appKey 缓存");
        } catch (Exception e) {
            this.lastError = e.getMessage();
            LOG.debug("[GW-Sign] Redis 重连失败：{}", e.getMessage());
        }
    }

    @Override
    public PublicKey currentKey(final String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        final String key = appKey.trim();
        final long now = System.currentTimeMillis();

        CachedEntry cached = cacheMap.get(key);
        if (cached != null && cached.expireAtMillis > now) {
            return cached.publicKey;
        }

        Object lock = lockMap.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = cacheMap.get(key);
            if (cached != null && cached.expireAtMillis > now) {
                return cached.publicKey;
            }
            return refresh(key, now, cached);
        }
    }

    @Override
    public void close() {
        if (redisConnection != null) {
            try {
                redisConnection.close();
            } catch (Exception e) {
                LOG.warn("[GW-Sign] 关闭 Redis 连接失败: {}", e.getMessage());
            }
        }
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                LOG.warn("[GW-Sign] 关闭 Redis 客户端失败: {}", e.getMessage());
            }
        }
        if (reconnectTimer != null) {
            try {
                reconnectTimer.shutdownNow();
            } catch (Exception e) {
                LOG.warn("[GW-Sign] 关闭重连定时器失败: {}", e.getMessage());
            }
        }
    }

    private PublicKey refresh(final String appKey, final long now, final CachedEntry previous) throws Exception {
        try {
            String pem = readPem(appKey);
            if (!StringUtils.hasText(pem)) {
                throw new IllegalStateException("biz public key pem is empty for appKey=" + appKey);
            }
            PublicKey parsed = parsePem(pem);
            cacheMap.put(appKey, new CachedEntry(parsed, now + cacheTtlMillis));
            LOG.debug("[GW-Sign] 公钥刷新成功 appKey={} source={} ttl={}s",
                    appKey, currentSource, cacheTtlMillis / 1000L);
            return parsed;
        } catch (Exception ex) {
            if (allowStaleOnRefreshFailure && previous != null) {
                cacheMap.put(appKey, new CachedEntry(previous.publicKey, now + failureRetryMillis));
                LOG.warn("[GW-Sign] 刷新公钥失败 appKey={}，继续使用旧缓存，{}s 后重试：{}",
                        appKey, failureRetryMillis / 1000L, ex.getMessage());
                return previous.publicKey;
            }
            if ("redis".equals(source)) {
                try {
                    String classpathPem = readClasspathPem(classpathLocation);
                    PublicKey parsed = parsePem(classpathPem);
                    cacheMap.put(appKey, new CachedEntry(parsed, now + failureRetryMillis));
                    LOG.warn("[GW-Sign] Redis 与 stale 均不可用 appKey={}，降级到 classpath PEM", appKey);
                    return parsed;
                } catch (Exception fallbackEx) {
                    // classpath 也失败，抛原异常
                }
            }
            throw ex;
        }
    }

    private String readPem(final String appKey) throws Exception {
        if ("redis".equals(source) && redisAvailable) {
            try {
                String redisKey = buildRedisKey(appKey);
                RedisCommands<String, String> redis = Objects.requireNonNull(redisConnection, "redisConnection").sync();
                String pem = redis.get(redisKey);
                if (!StringUtils.hasText(pem)) {
                    throw new IllegalStateException("redis key not found or empty: " + redisKey);
                }
                return pem;
            } catch (Exception e) {
                redisAvailable = false;
                lastRedisFailAtMillis = System.currentTimeMillis();
                lastError = e.getMessage();
                throw e;
            }
        }
        return readClasspathPem(classpathLocation);
    }

    /**
     * 根据 pattern 构造 Redis key.
     * <p>若 pattern 含 {@code %s}，替换为 appKey；否则直接使用 pattern（向后兼容）。
     *
     * @param appKey 应用标识
     * @return Redis key
     */
    private String buildRedisKey(final String appKey) {
        if (redisKeyPattern != null && redisKeyPattern.contains("%s")) {
            return String.format(redisKeyPattern, appKey);
        }
        return redisKeyPattern;
    }

    private String readClasspathPem(final String location) throws Exception {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private PublicKey parsePem(final String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private static final class CachedEntry {

        private final PublicKey publicKey;

        private final long expireAtMillis;

        private CachedEntry(final PublicKey publicKey, final long expireAtMillis) {
            this.publicKey = publicKey;
            this.expireAtMillis = expireAtMillis;
        }
    }
}
