/*
 * AdminConfigBizPublicKeyProvider —— 网关侧验签公钥提供器（admin plugin.config 源）。
 *
 * 公钥来源：ShenYu admin 下发的 springCloud 插件 config JSON 里的
 *   gw.springcloud.app-key.{appKey} 字段（由 erpm-pay-center 通过 admin REST API
 *   PUT /plugin/{id} 推送，admin websocket 自动同步到所有 bootstrap 实例）。
 *
 * 线程模型：currentKey() 是热路径，在 Netty EventLoop 线程上同步调用，
 *   仅读 volatile cacheMap，零 I/O、零锁。所有读 BaseDataCache 在后台守护线程。
 *
 * 缓存发布模型：syncFromBaseDataCache 在后台线程构建新 Map，完成后通过
 *   volatile 写 + Collections.unmodifiableMap 原子发布（safe publication）。
 *   读线程要么看到完整旧 Map，要么看到完整新 Map，无半更新中间态。
 *
 * 优雅降级：config 为空/非法JSON/零有效key 时保留旧缓存，避免瞬时故障放大为全站 401。
 *   就绪状态从 cacheMap.isEmpty() 派生（不维护独立 synced 字段，见 docs/sign-admin-config-pubkey-provider-修复方案.md §8）。
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 业务公钥提供器（admin plugin.config 源，多租户）。
 */
public final class AdminConfigBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdminConfigBizPublicKeyProvider.class);

    /**
     * springCloud 插件名（plugin 表 name 列）。
     *
     * <p>设计上锁定 springCloud 插件作为公钥载体。修改需同步调整：
     * <ul>
     *   <li>admin 侧 plugin 表对应记录的 name 列</li>
     *   <li>erpm-pay-center 推送公钥时指向的插件</li>
     * </ul>
     * 否则网关读取不到 config，将静默导致验签失败。
     */
    private static final String SPRINGCLOUD_PLUGIN_NAME = "springCloud";

    /**
     * admin plugin.config 里 per-appKey 公钥的字段前缀。
     *
     * <p>与 erpm-pay-center 的 PUT /plugin 推送约定一致（gw.springcloud.app-key.{appKey}）。
     * 修改需同步 pay-center 推送侧，否则下发的公钥无法被本类识别。
     */
    private static final String APP_KEY_PREFIX = "gw.springcloud.app-key.";

    /** 同步周期下限（秒），避免配置误填极小值导致频繁刷新 */
    private static final long MIN_REFRESH_INTERVAL_SECONDS = 5L;

    /**
     * 公钥缓存。volatile + unmodifiableMap 实现 safe publication；后台线程构建后整体发布。
     *
     * <p><b>就绪语义</b>：{@link #currentKey} 用 {@code cacheMap.isEmpty()} 判断 provider 是否就绪
     * （空 → "not initialized yet"，非空 → "synced but not found"）。这依赖一条不变量：
     * {@link #syncFromBaseDataCache} <b>永不发布空 Map</b>（零有效 key 时走优雅降级保留旧缓存）。
     * 一旦 cacheMap 首次变非空，单调永不再变空。若未来移除零有效 key 空判断，会直接导致
     * "admin 主动清空 → 全员误报 not initialized"——该症状是空判断不变量被破坏的直接信号，
     * 不应被额外状态字段屏蔽。
     */
    private volatile Map<String, PublicKey> cacheMap = Collections.emptyMap();

    private final ScheduledExecutorService refreshScheduler;

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
     * <p>仅读 {@link Map#get}，零 I/O 零锁。
     * admin 未下发的 appKey 直接抛异常 → 触发 HTTP 401。
     */
    @Override
    public PublicKey currentKey(final String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        final String normalized = appKey.trim();
        final PublicKey cached = cacheMap.get(normalized);
        if (cached != null) {
            return cached;
        }
        if (cacheMap.isEmpty()) {
            // 就绪判断从 cacheMap 自身派生：空 = 尚未完成首次有效同步
            throw new IllegalStateException(
                    "public key provider not initialized yet, appKey=" + appKey);
        }
        throw new IllegalStateException(
                "no public key for appKey=" + appKey + " (synced but not found in admin config)");
    }

    /** scheduler 入口（catch Throwable 避免 ScheduledExecutorService 静默终止任务） */
    private void syncSafe() {
        try {
            syncFromBaseDataCache();
        } catch (final Throwable t) {
            LOG.error("[GW-Sign] sync 异常：{}", t.getMessage(), t);
        }
    }

    /**
     * 从 BaseDataCache 读 springCloud config，解析 gw.springcloud.app-key.* 重建 cacheMap。
     *
     * <p>语义：
     * <ul>
     *   <li>config 为空 / 非法 JSON / 零有效 key：保留旧缓存（优雅降级）。
     *       对启动竞态、websocket 抖动、插件临时禁用、整体性 PEM 损坏等故障免疫，避免全站 401。</li>
     *   <li>config 成功解析且至少 1 条有效公钥：构建新 Map 后整体发布（不可变发布），原子替换旧 Map。
     *       被删的 appKey 自然从新 Map 消失 → 撤销在下一次有效同步时生效。</li>
     * </ul>
     *
     * <p><b>不维护独立的 synced 字段</b>：provider 是否就绪从 {@code cacheMap.isEmpty()} 派生。
     * 这依赖 {@code 永不发布空 Map} 这条不变量（见 {@link #cacheMap} 字段注释）。
     */
    private void syncFromBaseDataCache() {
        final PluginData pluginData = BaseDataCache.getInstance().obtainPluginData(SPRINGCLOUD_PLUGIN_NAME);
        final String configJson = pluginData == null ? null : pluginData.getConfig();
        if (configJson == null || configJson.trim().isEmpty()) {
            // 优雅降级 ①：空配置，保留旧缓存
            return;
        }

        final JsonObject config;
        try {
            config = JsonParser.parseString(configJson).getAsJsonObject();
        } catch (final Exception e) {
            // 优雅降级 ②：非法 JSON，保留旧缓存
            LOG.warn("[GW-Sign] springCloud plugin.config 非法 JSON，保留旧缓存：{}", e.getMessage());
            return;
        }

        // 构建新 Map，遍历完成后整体发布（原子替换：撤销 + 并发一次解决）
        final Map<String, PublicKey> next = new HashMap<>();
        int failed = 0;
        for (final String configKey : config.keySet()) {
            if (!configKey.startsWith(APP_KEY_PREFIX)) {
                continue;
            }
            final String appKey = configKey.substring(APP_KEY_PREFIX.length()).trim();  // 统一 trim
            final JsonElement element = config.get(configKey);
            if (appKey.isEmpty() || !element.isJsonPrimitive()) {
                continue;
            }
            final String pem = element.getAsString();
            if (pem == null || pem.trim().isEmpty()) {
                continue;
            }
            try {
                next.put(appKey, PemUtils.parsePem(pem.trim()));
            } catch (final Exception e) {
                failed++;
                LOG.warn("[GW-Sign] 解析公钥失败 appKey={}: {}", appKey, e.getMessage());
            }
        }

        // 优雅降级 ③：零有效 key，疑似整体性损坏，保留旧缓存
        if (next.isEmpty()) {
            LOG.warn("[GW-Sign] config 合法但解析出 0 条有效公钥（解析失败 {} 条），保留旧缓存（{} 条）",
                    failed, this.cacheMap.size());
            return;
        }

        final int prevSize = this.cacheMap.size();
        this.cacheMap = Collections.unmodifiableMap(next);  // 原子发布
        if (next.size() != prevSize || failed > 0) {
            LOG.info("[GW-Sign] 同步完成：{} 条公钥生效（解析失败 {} 条，上一版 {} 条）",
                    next.size(), failed, prevSize);
        } else {
            LOG.debug("[GW-Sign] 同步完成：{} 条公钥（与上一版一致）", next.size());
        }
    }

    @Override
    public void close() {
        refreshScheduler.shutdownNow();
    }
}
