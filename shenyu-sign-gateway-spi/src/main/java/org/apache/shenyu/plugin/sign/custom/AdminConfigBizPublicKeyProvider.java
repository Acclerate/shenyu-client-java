/*
 * AdminConfigBizPublicKeyProvider —— 网关侧验签公钥提供器（admin plugin.config 源）。
 *
 * 公钥来源：ShenYu admin 下发的 springCloud 插件 config JSON 里的
 *   gw.springcloud.app-key.{appKey} 字段（由 erpm-pay-center 通过 admin REST API
 *   PUT /plugin/{id} 推送，admin websocket 自动同步到所有 bootstrap 实例）。
 *
 * 线程模型：currentKey() 是热路径，在 Netty EventLoop 线程上同步调用，
 *   仅读 ConcurrentHashMap，零 I/O、零锁。所有读 BaseDataCache 在后台守护线程。
 */
package org.apache.shenyu.plugin.sign.custom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.shenyu.common.dto.PluginData;
import org.apache.shenyu.plugin.base.cache.BaseDataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 业务公钥提供器（admin plugin.config 源，多租户）。
 */
public final class AdminConfigBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdminConfigBizPublicKeyProvider.class);

    /** admin plugin.config 里 per-appKey 公钥的字段前缀 */
    private static final String APP_KEY_PREFIX = "gw.springcloud.app-key.";

    /** springCloud 插件名（plugin 表 name 列） */
    private static final String SPRINGCLOUD_PLUGIN_NAME = "springCloud";

    /** 同步周期下限（秒），避免配置误填极小值导致频繁刷新 */
    private static final long MIN_REFRESH_INTERVAL_SECONDS = 5L;

    private final ConcurrentHashMap<String, PublicKey> cacheMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService refreshScheduler;

    /** config 非空（admin 已推送过 springCloud config） */
    private volatile boolean synced = false;

    /**
     * @param refreshIntervalSeconds 后台线程从 BaseDataCache 同步的周期（秒）
     */
    public AdminConfigBizPublicKeyProvider(final long refreshIntervalSeconds) {
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "gw-sign-adminconfig-refresh");
            t.setDaemon(true);
            return t;
        });
        final long interval = Math.max(MIN_REFRESH_INTERVAL_SECONDS, refreshIntervalSeconds);
        this.refreshScheduler.scheduleWithFixedDelay(this::syncSafe, 1L, interval, TimeUnit.SECONDS);
        LOG.info("[GW-Sign] AdminConfig 公钥源已启用 refresh={}s", interval);
    }

    /** 便捷构造（默认 30s 周期） */
    public AdminConfigBizPublicKeyProvider() {
        this(30L);
    }

    /**
     * 热路径：获取指定 appKey 的验签公钥。
     *
     * <p>仅读 {@link ConcurrentHashMap#get}，零 I/O 零锁。
     * admin 未下发的 appKey 直接抛异常 → 触发 HTTP 401。
     */
    @Override
    public PublicKey currentKey(final String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        final PublicKey cached = cacheMap.get(appKey.trim());
        if (cached != null) {
            return cached;
        }
        if (!synced) {
            throw new IllegalStateException(
                    "public key provider not initialized yet, appKey=" + appKey);
        }
        throw new IllegalStateException(
                "no public key for appKey=" + appKey + " (synced but not found in admin config)");
    }

    /** scheduler 入口（catch Throwable 避免 ScheduledExecutorService 静默终止任务） */
    private void syncSafe() {
        try {
            synced = syncFromBaseDataCache();
        } catch (final Throwable t) {
            LOG.error("[GW-Sign] sync 异常：{}", t.getMessage(), t);
        }
    }

    /** 从 BaseDataCache 读 springCloud config，解析 gw.springcloud.app-key.* 写入 cacheMap。
     *
     * @return true 如果 config 非空（admin 已推送 springCloud config）
     */
    private boolean syncFromBaseDataCache() {
        final PluginData pluginData = BaseDataCache.getInstance().obtainPluginData(SPRINGCLOUD_PLUGIN_NAME);
        final String configJson = pluginData == null ? null : pluginData.getConfig();
        if (configJson == null || configJson.trim().isEmpty()) {
            return false;
        }

        final JsonObject config;
        try {
            config = JsonParser.parseString(configJson).getAsJsonObject();
        } catch (final Exception e) {
            LOG.warn("[GW-Sign] springCloud plugin.config 不是合法 JSON，跳过本次同步：{}", e.getMessage());
            return false;
        }

        int refreshed = 0;
        for (final String configKey : config.keySet()) {
            if (!configKey.startsWith(APP_KEY_PREFIX)) {
                continue;
            }
            final String appKey = configKey.substring(APP_KEY_PREFIX.length());
            final JsonElement element = config.get(configKey);
            if (appKey.isEmpty() || !element.isJsonPrimitive()) {
                continue;
            }
            final String pem = element.getAsString();
            if (pem == null || pem.trim().isEmpty()) {
                continue;
            }
            try {
                cacheMap.put(appKey, PemUtils.parsePem(pem.trim()));
                refreshed++;
            } catch (final Exception e) {
                LOG.warn("[GW-Sign] 解析公钥失败 appKey={}: {}", appKey, e.getMessage());
            }
        }

        if (refreshed > 0) {
            LOG.info("[GW-Sign] 同步 {} 条公钥（cacheMap 总数={}）", refreshed, cacheMap.size());
        }
        return true;
    }

    @Override
    public void close() {
        refreshScheduler.shutdownNow();
    }
}
