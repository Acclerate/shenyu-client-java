/*
 * HttpBizPublicKeyProvider —— 网关侧验签公钥提供器（HTTP 源，多租户）。
 *
 * 替代原 DynamicBizPublicKeyProvider（Redis 源）。根据请求头 X-Pay-App-Key 的 appKey，
 * 通过 Apache HttpClient 向 demo 的 GET /sign/public-key/{appKey} 拉取 RSA 公钥。
 *
 * 【线程模型 — WebFlux EventLoop 安全】
 *   ShenYu SignPlugin 在 Netty EventLoop 线程上【同步】调用 currentKey（线程名 shenyu-netty-epoll-*，
 *   日志已实证）。EventLoop 线程数 = CPU 核心数（8 核机器仅 8 个），任何一个被阻塞都会导致
 *   绑定在其上的数百并发连接全部排队，网关吞吐量断崖式下跌。
 *
 *   本类的 currentKey() 热路径【零 I/O、零锁】：
 *     1. 缓存命中（fresh 或 stale）→ ConcurrentHashMap.get()，纳秒级，不阻塞 EventLoop
 *     2. 缓存完全 miss（全新 appKey 首次请求）→ 返回构造期预加载的 classpath 兜底公钥（纯内存读）
 *   所有 HTTP I/O 都在后台守护线程 refreshScheduler（线程名 gw-sign-http-refresh）上执行，
 *   每 refreshIntervalSeconds 秒刷新一次已知 appKey 的公钥入缓存。
 *
 *   注意：由于 ShenYu SignService 接口是同步的（返回 VerifyResult 而非 Mono<VerifyResult>），
 *   无法使用 Mono.fromCallable().subscribeOn(boundedElastic) 响应式包装。
 *   本设计在同步接口约束下实现了等效效果：EventLoop 永远不做网络 I/O。
 *
 * 兜底链：缓存命中(stale 可用) → classpath 预加载公钥 → 抛异常(触发 401)。
 * 后台刷新失败时保留 stale 缓存（allowStaleOnRefreshFailure=true）。
 */
package org.apache.shenyu.plugin.sign.custom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.shenyu.common.dto.PluginData;
import org.apache.shenyu.plugin.base.cache.BaseDataCache;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 动态业务公钥提供器（多租户，HTTP 源）。
 *
 * <p>根据请求头 {@code X-Pay-App-Key} 的 appKey，通过 Apache HttpClient 向 demo 的
 * {@code GET /sign/public-key/{appKey}} 拉取 RSA 公钥，支持多业务方各自独立密钥对。
 *
 * <p><b>线程模型（WebFlux EventLoop 安全）</b>：网关 SignPlugin 在 Netty EventLoop 线程上同步调用
 * {@link #currentKey(String)}（日志实证线程名 shenyu-netty-epoll-*）。EventLoop 线程数 = CPU 核心数，
 * 任何一个被阻塞都会导致数百并发连接排队。
 *
 * <p>本类热路径 {@link #currentKey} 【零 I/O、零锁】：
 * <ul>
 *   <li>缓存命中（fresh 或 stale）→ {@link ConcurrentHashMap#get}，纳秒级</li>
 *   <li>缓存 miss（全新 appKey）→ 返回构造期预加载的 classpath 兜底公钥（纯内存读）</li>
 * </ul>
 * 所有 HTTP I/O 都在后台守护线程 {@code refreshScheduler}（线程名 gw-sign-http-refresh）上执行。
 *
 * <p>由于 ShenYu {@code SignService} 接口是同步的（返回 {@code VerifyResult} 而非 {@code Mono}），
 * 无法使用 {@code Mono.fromCallable().subscribeOn(boundedElastic)} 响应式包装。
 * 本设计在同步接口约束下实现了等效效果：EventLoop 永远不做网络 I/O。
 *
 * <p>兜底链：缓存命中(stale 可用) → classpath 预加载公钥 → 抛异常(触发 401)。
 */
public final class HttpBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpBizPublicKeyProvider.class);

    private final String source;

    private final String classpathLocation;

    /** 以下为支持 admin(springCloud plugin.config) 热更新的可变字段 */
    private volatile String baseUrl;

    private volatile String pathPattern;

    private volatile long cacheTtlMillis;

    private volatile long failureRetryMillis;

    private volatile boolean allowStaleOnRefreshFailure;

    private final int connectTimeoutMs;

    private final int readTimeoutMs;

    private final int maxConnections;

    private volatile CloseableHttpClient httpClient;

    private volatile RequestConfig requestConfig;

    /** admin(springCloud plugin.config) 下发的 per-appKey 静态公钥兜底（HTTP 刷新失败时降级） */
    private final ConcurrentHashMap<String, String> adminConfiguredPemMap = new ConcurrentHashMap<>();

    /** Per-appKey 缓存 */
    private final ConcurrentHashMap<String, CachedEntry> cacheMap = new ConcurrentHashMap<>();

    /** 构造期预加载的 classpath 兜底公钥（热路径缓存 miss 时返回，零 I/O） */
    private final PublicKey classpathFallbackKey;

    /** 运行期收集到的 appKey 集合，供后台刷新遍历（首次见到即加入） */
    private final Set<String> knownAppKeys = new CopyOnWriteArraySet<>();

    /** 后台刷新确认不存在的 appKey（HTTP 404），热路径直接拒绝，不走 classpath 兜底 */
    private final Set<String> notFoundAppKeys = new CopyOnWriteArraySet<>();

    private volatile boolean sourceAvailable = true;

    private ScheduledExecutorService refreshScheduler;

    HttpBizPublicKeyProvider(
            final String source,
            final String classpathLocation,
            final String baseUrl,
            final String pathPattern,
            final int connectTimeoutMs,
            final int readTimeoutMs,
            final int maxConnections,
            final long refreshIntervalSeconds,
            final long cacheTtlSeconds,
            final long failureRetrySeconds,
            final boolean allowStaleOnRefreshFailure
    ) {
        this.source = source == null ? "classpath" : source.trim().toLowerCase();
        this.classpathLocation = classpathLocation;
        this.baseUrl = baseUrl;
        this.pathPattern = pathPattern;
        this.connectTimeoutMs = Math.max(100, connectTimeoutMs);
        this.readTimeoutMs = Math.max(100, readTimeoutMs);
        this.cacheTtlMillis = Math.max(1L, cacheTtlSeconds) * 1000L;
        this.failureRetryMillis = Math.max(1L, failureRetrySeconds) * 1000L;
        this.allowStaleOnRefreshFailure = allowStaleOnRefreshFailure;
        this.maxConnections = maxConnections;

        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConnections);
        cm.setDefaultMaxPerRoute(maxConnections);
        this.httpClient = HttpClients.custom().setConnectionManager(cm).build();
        this.requestConfig = RequestConfig.custom()
                .setConnectTimeout(this.connectTimeoutMs)
                .setSocketTimeout(this.readTimeoutMs)
                .setConnectionRequestTimeout(this.connectTimeoutMs)
                .build();

        // 预加载 classpath 兜底公钥（构造期完成，热路径零 I/O）
        this.classpathFallbackKey = loadClasspathKey();

        if ("http".equals(this.source)) {
            this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gw-sign-http-refresh");
                t.setDaemon(true);
                return t;
            });
            // 后台主动刷新：把网络 I/O 移出 EventLoop 热路径；首次延迟 1s 让 Spring 启动完成
            long interval = Math.max(1L, refreshIntervalSeconds);
            this.refreshScheduler.scheduleWithFixedDelay(this::refreshAll, 1, interval, TimeUnit.SECONDS);
            LOG.info("[GW-Sign] 公钥源已启用 HTTP 模式 baseUrl={} pattern={} refresh={}s ttl={}s",
                    baseUrl, pathPattern, interval, cacheTtlSeconds);
        } else {
            // classpath 模式：不做 HTTP，纯 classpath PEM（仍走缓存）
            LOG.info("[GW-Sign] 公钥源使用 classpath 模式 path={}", classpathLocation);
        }
    }

    /**
     * 从 admin 下发的 springCloud 插件 config 读取 sign SPI 配置（热更新）。
     * 在现有 gw-sign-http-refresh 守护线程的 refreshAll() 开头调用，零新线程、零 EventLoop 阻塞。
     * env var 作为默认值；admin 配了才会覆盖。
     */
    private void syncAdminConfig() {
        try {
            PluginData pd = BaseDataCache.getInstance().obtainPluginData("springCloud");
            String json = pd == null ? null : pd.getConfig();
            if (json == null || json.trim().isEmpty()) {
                return;
            }
            JsonObject cfg = JsonParser.parseString(json).getAsJsonObject();

            if (cfg.has("gw.springcloud.http.base-url")) {
                String bu = cfg.get("gw.springcloud.http.base-url").getAsString().trim();
                if (!bu.isEmpty() && !bu.equals(this.baseUrl)) {
                    swapHttpClient(bu);
                }
            }
            if (cfg.has("gw.springcloud.http.path-pattern")) {
                String pp = cfg.get("gw.springcloud.http.path-pattern").getAsString().trim();
                if (!pp.isEmpty()) {
                    this.pathPattern = pp;
                }
            }
            if (cfg.has("gw.springcloud.cache.ttl-seconds")) {
                this.cacheTtlMillis = Math.max(1L, cfg.get("gw.springcloud.cache.ttl-seconds").getAsLong()) * 1000L;
            }
            if (cfg.has("gw.springcloud.cache.failure-retry-seconds")) {
                this.failureRetryMillis = Math.max(1L, cfg.get("gw.springcloud.cache.failure-retry-seconds").getAsLong()) * 1000L;
            }
            if (cfg.has("gw.springcloud.cache.allow-stale-on-refresh-failure")) {
                this.allowStaleOnRefreshFailure = cfg.get("gw.springcloud.cache.allow-stale-on-refresh-failure").getAsBoolean();
            }
            if (cfg.has("gw.springcloud.pre-warm-app-keys")) {
                String pw = cfg.get("gw.springcloud.pre-warm-app-keys").getAsString();
                if (pw != null) {
                    for (String k : pw.split(",")) {
                        String t = k.trim();
                        if (!t.isEmpty()) {
                            knownAppKeys.add(t);
                        }
                    }
                }
            }
            // per-appKey 静态公钥兜底（admin 直配）：key 形如 gw.springcloud.app-key.{appKey}
            for (String key : cfg.keySet()) {
                if (key.startsWith("gw.springcloud.app-key.")) {
                    String appKey = key.substring("gw.springcloud.app-key.".length());
                    String pem = cfg.get(key).getAsString();
                    if (appKey.isEmpty() || pem == null || pem.trim().isEmpty()) {
                        continue;
                    }
                    adminConfiguredPemMap.put(appKey, pem.trim());
                    knownAppKeys.add(appKey);
                }
            }
        } catch (Exception e) {
            LOG.debug("[GW-Sign] syncAdminConfig 跳过（admin 未配置或 JSON 格式错误）", e);
        }
    }

    /** 热换 HttpClient（baseUrl 变更时）：关旧建新，旧连接在下次 GC 释放 */
    private synchronized void swapHttpClient(final String newBaseUrl) {
        this.baseUrl = newBaseUrl;
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConnections);
        cm.setDefaultMaxPerRoute(maxConnections);
        CloseableHttpClient newClient = HttpClients.custom().setConnectionManager(cm).build();
        RequestConfig newReq = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .build();
        CloseableHttpClient old = this.httpClient;
        this.httpClient = newClient;
        this.requestConfig = newReq;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        LOG.info("[GW-Sign] HTTP 客户端已热换 baseUrl={}", newBaseUrl);
    }

    /** 后台刷新：遍历已知 appKey，在守护线程上拉取公钥入缓存（带 stale 兜底） */
    private void refreshAll() {
        syncAdminConfig();
        for (String appKey : knownAppKeys) {
            try {
                String pem = fetchPem(appKey);
                PublicKey parsed = parsePem(pem);
                cacheMap.put(appKey, new CachedEntry(parsed, System.currentTimeMillis() + cacheTtlMillis));
                notFoundAppKeys.remove(appKey);
                sourceAvailable = true;
            } catch (AppKeyNotFoundException e) {
                notFoundAppKeys.add(appKey);
                LOG.warn("[GW-Sign] 后台刷新确认 appKey 不存在(404)：{}", appKey);
            } catch (Exception e) {
                // HTTP 刷新失败 → 尝试 admin 下发的 per-appKey 静态公钥兜底
                String adminPem = adminConfiguredPemMap.get(appKey);
                if (adminPem != null) {
                    try {
                        PublicKey parsed = parsePem(adminPem);
                        cacheMap.put(appKey, new CachedEntry(parsed, System.currentTimeMillis() + cacheTtlMillis));
                        notFoundAppKeys.remove(appKey);
                        LOG.info("[GW-Sign] 后台刷新失败，降级到 admin 静态公钥 appKey={}", appKey);
                        continue;
                    } catch (Exception pe) {
                        LOG.warn("[GW-Sign] admin 静态公钥解析失败 appKey={}：{}", appKey, pe.getMessage());
                    }
                }
                LOG.warn("[GW-Sign] 后台刷新公钥失败 appKey={}：{}", appKey, e.getMessage());
            }
        }
    }

    /**
     * 热路径：获取指定 appKey 的验签公钥。
     *
     * <p><b>EventLoop 安全</b>：本方法仅做 {@link ConcurrentHashMap#get} 和内存读，
     * 绝不做 HTTP I/O、文件 I/O 或 synchronized 锁。缓存 miss 时返回构造期预加载的
     * classpath 兜底公钥，后台 scheduler 会在下个刷新周期（≤{@code refreshIntervalSeconds}秒）
     * 异步拉取真实公钥入缓存。
     *
     * @param appKey 业务标识（对应 X-Pay-App-Key 头）
     * @return RSA 公钥
     * @throws Exception 仅当无缓存且无 classpath 兜底时抛出（触发 401）
     */
    @Override
    public PublicKey currentKey(final String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        final String key = appKey.trim();
        // 运行期收集，后台刷新接管后续保鲜（首见即加入）
        knownAppKeys.add(key);

        // 后台刷新已确认该 appKey 不存在（HTTP 404）→ 直接拒绝，不走 classpath 兜底
        if (notFoundAppKeys.contains(key)) {
            throw new AppKeyNotFoundException("appKey not found: " + key);
        }

        // 热路径：仅读缓存（ConcurrentHashMap.get），零 I/O，零锁
        CachedEntry cached = cacheMap.get(key);
        if (cached != null) {
            // 缓存命中（fresh 或 stale）—— 立即返回
            // stale 缓存：后台 scheduler 会刷新，这里先返回旧值不阻塞 EventLoop
            return cached.publicKey;
        }

        // 缓存完全 miss（全新 appKey 首次请求，或 demo 上次刷新宕机且 stale 已被清除）
        // ⚠️ 绝不在 EventLoop 上做同步 HTTP —— 返回预加载的 classpath 兜底公钥
        // 后台 scheduler 会在下个刷新周期（≤15s）拉取真实公钥入缓存
        if (classpathFallbackKey != null) {
            LOG.debug("[GW-Sign] 缓存 miss appKey={}，使用 classpath 兜底公钥（后台 scheduler 将异步拉取）", key);
            return classpathFallbackKey;
        }
        throw new IllegalStateException(
                "no cached key for appKey=" + key + " and no classpath fallback available");
    }

    @Override
    public void close() {
        if (refreshScheduler != null) {
            try {
                refreshScheduler.shutdownNow();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                LOG.warn("[GW-Sign] 关闭 HttpClient 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 预热指定 appKey 的公钥缓存（在启动线程上同步拉取，不阻塞 EventLoop）。
     *
     * <p>应在 Spring 容器启动后、网关开始接收流量前调用。
     * 预热失败不抛异常（降级到 classpath 兜底公钥）。
     *
     * @param appKeys 需要预热的 appKey 列表
     */
    public void preWarm(final String... appKeys) {
        if (appKeys == null || appKeys.length == 0) {
            return;
        }
        for (String appKey : appKeys) {
            if (appKey == null || appKey.trim().isEmpty()) {
                continue;
            }
            String key = appKey.trim();
            knownAppKeys.add(key);
            try {
                String pem = readPem(key);
                if (StringUtils.hasText(pem)) {
                    PublicKey parsed = parsePem(pem);
                    cacheMap.put(key, new CachedEntry(parsed, System.currentTimeMillis() + cacheTtlMillis));
                    notFoundAppKeys.remove(key);
                    LOG.info("[GW-Sign] 预热公钥成功 appKey={}", key);
                }
            } catch (AppKeyNotFoundException e) {
                notFoundAppKeys.add(key);
                LOG.warn("[GW-Sign] 预热确认 appKey 不存在(404)：{}", key);
            } catch (Exception e) {
                LOG.warn("[GW-Sign] 预热公钥失败 appKey={}：{}（降级到 classpath 兜底）", key, e.getMessage());
            }
        }
    }

    /** 构造期预加载 classpath 兜底公钥（失败返回 null，热路径 miss 时抛异常） */
    private PublicKey loadClasspathKey() {
        try {
            String pem = readClasspathPem(classpathLocation);
            if (StringUtils.hasText(pem)) {
                PublicKey key = parsePem(pem);
                LOG.info("[GW-Sign] classpath 兜底公钥已预加载 location={}", classpathLocation);
                return key;
            }
        } catch (Exception e) {
            LOG.warn("[GW-Sign] classpath 兜底公钥加载失败 location={}：{}", classpathLocation, e.getMessage());
        }
        return null;
    }

    private PublicKey refresh(final String appKey, final long now, final CachedEntry previous) throws Exception {
        try {
            String pem = readPem(appKey);
            if (!StringUtils.hasText(pem)) {
                throw new IllegalStateException("biz public key pem is empty for appKey=" + appKey);
            }
            PublicKey parsed = parsePem(pem);
            cacheMap.put(appKey, new CachedEntry(parsed, now + cacheTtlMillis));
            LOG.debug("[GW-Sign] 公钥刷新成功 appKey={} source={} ttl={}s", appKey, currentSource(), cacheTtlMillis / 1000L);
            return parsed;
        } catch (AppKeyNotFoundException e) {
            throw e; // appKey 不存在：不兜底，直接失败
        } catch (Exception ex) {
            if (allowStaleOnRefreshFailure && previous != null) {
                cacheMap.put(appKey, new CachedEntry(previous.publicKey, now + failureRetryMillis));
                LOG.warn("[GW-Sign] 刷新公钥失败 appKey={}，继续使用旧缓存，{}s 后重试：{}",
                        appKey, failureRetryMillis / 1000L, ex.getMessage());
                return previous.publicKey;
            }
            // classpath 兜底（所有 appKey 共用同一固定公钥）
            try {
                String classpathPem = readClasspathPem(classpathLocation);
                PublicKey parsed = parsePem(classpathPem);
                cacheMap.put(appKey, new CachedEntry(parsed, now + failureRetryMillis));
                LOG.warn("[GW-Sign] HTTP 与 stale 均不可用 appKey={}，降级到 classpath PEM", appKey);
                return parsed;
            } catch (Exception fallbackEx) {
                // classpath 也失败，抛原异常
            }
            throw ex;
        }
    }

    private String readPem(final String appKey) throws Exception {
        if ("http".equals(source)) {
            try {
                return fetchPem(appKey);
            } catch (Exception e) {
                sourceAvailable = false;
                throw e;
            }
        }
        return readClasspathPem(classpathLocation);
    }

    /**
     * 通过 Apache HttpClient 向 demo 拉取公钥。
     *
     * @param appKey 应用标识
     * @return PEM 字符串
     * @throws AppKeyNotFoundException appKey 不存在（HTTP 404）
     * @throws Exception               网络 / 解析 / 其他错误
     */
    private String fetchPem(final String appKey) throws Exception {
        String url = baseUrl + pathPattern.replace("%s", URLEncoder.encode(appKey, "UTF-8"));
        HttpGet httpGet = new HttpGet(url);
        httpGet.setConfig(requestConfig);
        httpGet.setHeader("Accept", "application/json");
        httpGet.setHeader("X-Source", "shenyu-sign-spi");
        try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status == 404) {
                throw new AppKeyNotFoundException("appKey not found: " + appKey);
            }
            if (status != 200) {
                throw new IllegalStateException("demo returned HTTP " + status + " for appKey=" + appKey);
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            // 兼容 erpm-pay-center R<T> 包装格式：{code, message, data: {publicKey}}
            if (!json.has("data") || json.get("data").isJsonNull()) {
                throw new IllegalStateException("demo response missing data field for appKey=" + appKey);
            }
            JsonObject data = json.getAsJsonObject("data");
            if (!data.has("publicKey") || data.get("publicKey").isJsonNull()) {
                throw new IllegalStateException("demo response missing publicKey field for appKey=" + appKey);
            }
            String pem = data.get("publicKey").getAsString();
            if (!StringUtils.hasText(pem)) {
                throw new IllegalStateException("demo publicKey is empty for appKey=" + appKey);
            }
            return pem;
        }
    }

    private String currentSource() {
        return "http".equals(source) ? (sourceAvailable ? "http" : "classpath(fallback)") : "classpath";
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

    /** appKey 不存在（HTTP 404）专用异常，不触发 stale / classpath 兜底 */
    public static final class AppKeyNotFoundException extends RuntimeException {
        public AppKeyNotFoundException(final String message) {
            super(message);
        }
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
