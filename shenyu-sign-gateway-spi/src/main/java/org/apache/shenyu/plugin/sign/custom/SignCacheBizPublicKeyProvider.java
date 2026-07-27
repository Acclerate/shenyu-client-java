/*
 * SignCacheBizPublicKeyProvider —— 网关侧验签公钥提供器（app_auth 源，websocket push）。
 *
 * 数据源：ShenYu admin 的 app_auth 表（app_secret 列承载 RSA 公钥裸 Base64，无 PEM 头尾标记）。
 *   admin 写库后通过 websocket 推送 APP_AUTH 分组事件，本类作为 AuthDataSubscriber
 *   回调接收 AppAuthData，存入 appAuthDataMap（数据源/truth）。
 *
 * 单订阅者原则（铁律 4）：本类同时实现 BizPublicKeyProvider 和 AuthDataSubscriber，
 *   是全工程唯一的 AuthDataSubscriber 实现外的订阅者。数据源与 L2 缓存由同一个对象、
 *   同一个回调线程维护，杜绝多订阅者之间的 refresh 顺序竞态（lost update）。
 *
 * 线程模型：
 *   - currentKey() 热路径：在 Netty EventLoop 线程上同步调用，仅读两个 Map（L2 + 数据源），
 *     零 I/O。L2 用 volatile + unmodifiableMap 实现 safe publication；数据源是 ConcurrentHashMap。
 *   - onSubscribe/unSubscribe/refresh：在 websocket 接收线程上调用，唯一写入者。
 *
 * 就绪语义（D2）：everSynced 标志首次收到数据后置 true 且永不再变 false。
 *   全量 REFRESH 的 clear 窗口内 everSynced 仍为 true，HealthIndicator 不会误判 DOWN
 *   导致 K8s 误摘流（裸用 isEmpty() 会在 refresh 清空瞬间产生假阴性）。
 *
 * enabled 语义（D3）：L2 只缓存 PublicKey（省 parseBase64），enabled 每次实时从数据源读。
 *   禁用/回滚仅受 websocket 推送延迟（秒级）影响，无额外缓存层延迟。
 *
 * 安全判空（铁律 5）：AppAuthData.getEnabled() 返回 Boolean 包装类型，
 *   一律用 Boolean.TRUE.equals(...) 判定，禁用 !getEnabled()（null 时 NPE）。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.common.dto.AppAuthData;
import org.apache.shenyu.sync.data.api.AuthDataSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务公钥提供器（app_auth 源，websocket push，多租户）。
 *
 * <p>同时实现 {@link BizPublicKeyProvider} 和 {@link AuthDataSubscriber}，
 * 单一对象统管数据源与 L2 缓存，是本 SPI 唯一的 AuthDataSubscriber 实现。
 */
public final class SignCacheBizPublicKeyProvider implements BizPublicKeyProvider, AuthDataSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(SignCacheBizPublicKeyProvider.class);

    /**
     * 数据源（truth）：appKey → AppAuthData。
     *
     * <p>由 {@link #onSubscribe}/{@link #unSubscribe}/{@link #refresh} 在 websocket 线程维护，
     * 由 {@link #currentKey} 在 EventLoop 线程并发读。ConcurrentHashMap 保证读的内存可见性。
     */
    private final ConcurrentHashMap<String, AppAuthData> appAuthDataMap = new ConcurrentHashMap<>();

    /**
     * L2 缓存：appKey → PublicKey（parseBase64 结果，省去每次验签的 Base64+KeyFactory 开销）。
     *
     * <p>volatile + Collections.unmodifiableMap 实现 safe publication：写线程构建新 Map 后整体发布，
     * 读线程要么看到完整旧 Map，要么看到完整新 Map，无半更新中间态。
     */
    private volatile Map<String, PublicKey> publicKeyMap = Collections.emptyMap();

    /**
     * 就绪标志：首次收到非空数据后置 true，永不再变 false。
     *
     * <p>用于 {@link AppAuthHealthIndicator} 判断网关是否已完成首次 websocket 同步。
     * 不用 {@link #appAuthDataMap}.isEmpty() 的原因：全量 REFRESH 时 {@link #refresh}
     * 会 clear 数据源，此时 isEmpty()=true 但 everSynced 仍 true，避免 HealthIndicator
     * 在 refresh 清空窗口误报 DOWN 导致 K8s 摘流。
     */
    private volatile boolean everSynced = false;

    /**
     * 热路径：获取指定 appKey 的验签公钥。
     *
     * <p>流程：
     * <ol>
     *   <li>L2 命中 → 实时查数据源 enabled（D3）：
     *     <ul>
     *       <li>enabled=true → 返回公钥</li>
     *       <li>enabled=false → 抛 disabled</li>
     *       <li>数据源已无此 key（被 unSubscribe 删除）→ 抛 not found（修正语义：非 disabled）</li>
     *     </ul>
     *   </li>
     *   <li>L2 miss → 查数据源：
     *     <ul>
     *       <li>null + everSynced=false → 抛 not ready（首启未同步）</li>
     *       <li>null + everSynced=true → 抛 not found（业务方未配置）</li>
     *       <li>enabled=false → 抛 disabled</li>
     *       <li>enabled=true → parseBase64 + copy-on-write 回填 L2</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param appKey 应用标识（来自请求头 X-Pay-App-Key）
     * @return 公钥
     * @throws Exception appKey 非法、未就绪、未配置、被禁用、公钥解析失败时抛出
     */
    @Override
    public PublicKey currentKey(final String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        final String normalized = appKey.trim();

        // 1. L2 命中（热路径，绝大多数请求走这里）
        final PublicKey cached = publicKeyMap.get(normalized);
        if (cached != null) {
            // D3：enabled 实时查数据源，不信任 L2 里的快照
            final AppAuthData current = appAuthDataMap.get(normalized);
            if (current == null) {
                // L2 有公钥但数据源已无此 key（被 unSubscribe 删除）→ not found，非 disabled
                LOG.warn("[GW-Sign] L2 命中但数据源已无 appKey={}（可能刚被删除）", normalized);
                throw new IllegalStateException("no app_auth record for appKey=" + normalized);
            }
            if (!Boolean.TRUE.equals(current.getEnabled())) {
                throw new IllegalStateException("appKey=" + normalized + " is disabled in app_auth");
            }
            return cached;
        }

        // 2. L2 miss → 从数据源回源
        final AppAuthData authData = appAuthDataMap.get(normalized);
        if (authData == null) {
            // 就绪语义：everSynced=false 表示尚未完成首次 websocket 同步
            if (!everSynced) {
                throw new IllegalStateException(
                        "public key provider not initialized yet (app_auth data not synced), appKey=" + normalized);
            }
            throw new IllegalStateException(
                    "no app_auth record for appKey=" + normalized + " (synced but not found)");
        }
        if (!Boolean.TRUE.equals(authData.getEnabled())) {
            throw new IllegalStateException("appKey=" + normalized + " is disabled in app_auth");
        }

        final String base64Key = authData.getAppSecret();
        if (base64Key == null || base64Key.trim().isEmpty()) {
            throw new IllegalStateException("app_secret (public key base64) is empty for appKey=" + normalized);
        }

        // 3. parse Base64 + copy-on-write 回填 L2
        //    并发 miss 同一 appKey 时可能重复 parse，但结果幂等，可接受（换取热路径零锁）
        final PublicKey parsed;
        try {
            parsed = PemUtils.parsePem(base64Key.trim());
        } catch (final Exception e) {
            // 公钥非法不污染 L2，下次请求仍走 parse 重试
            LOG.warn("[GW-Sign] 解析公钥失败 appKey={}: {}", normalized, e.getMessage());
            throw new IllegalStateException("invalid public key (base64) for appKey=" + normalized, e);
        }

        final Map<String, PublicKey> nextMap = new HashMap<>(publicKeyMap);
        nextMap.put(normalized, parsed);
        publicKeyMap = Collections.unmodifiableMap(nextMap);
        LOG.info("[GW-Sign] app_auth 公钥加载/更新 appKey={}", normalized);
        return parsed;
    }

    // ---------------------------------------------------------------------
    // AuthDataSubscriber 实现（单订阅者，唯一写入者）
    // ---------------------------------------------------------------------

    /**
     * admin 推送 AppAuthData 增量（CREATE/UPDATE）时回调。
     *
     * <p>语义：更新数据源 + 失效 L2 对应条目（下次 currentKey 重新 parse）。
     * 首次收到数据时置 everSynced=true。
     */
    @Override
    public void onSubscribe(final AppAuthData data) {
        if (data == null || data.getAppKey() == null || data.getAppKey().trim().isEmpty()) {
            return;
        }
        final String appKey = data.getAppKey().trim();
        appAuthDataMap.put(appKey, data);
        evictL2(appKey);
        if (!everSynced) {
            everSynced = true;
            LOG.info("[GW-Sign] 首次收到 app_auth 数据，provider 标记为已就绪");
        }
        LOG.debug("[GW-Sign] onSubscribe 更新 appKey={}", appKey);
    }

    /**
     * admin 推送 AppAuthData 删除（DELETE）时回调。
     *
     * <p>语义：从数据源移除 + 失效 L2 对应条目。
     */
    @Override
    public void unSubscribe(final AppAuthData data) {
        if (data == null || data.getAppKey() == null || data.getAppKey().trim().isEmpty()) {
            return;
        }
        final String appKey = data.getAppKey().trim();
        appAuthDataMap.remove(appKey);
        evictL2(appKey);
        LOG.info("[GW-Sign] unSubscribe 移除 appKey={}", appKey);
    }

    /**
     * 全量 REFRESH 时回调（如新网关节点握手触发 syncData、admin 手动同步数据）。
     *
     * <p>语义：清空数据源 + 清空 L2。ShenYu Admin 紧随其后会逐条 onSubscribe 重新填充。
     *
     * <p><b>关键：不清 everSynced</b>。原因：refresh 清空到逐条重填之间有一个亚秒级窗口，
     * 若清空 everSynced 会让 HealthIndicator 在此窗口误报 DOWN，K8s readinessProbe 可能误摘流。
     * everSynced 语义是"曾完成过同步"，一旦 true 永不变 false。
     */
    @Override
    public void refresh() {
        final int prevSize = appAuthDataMap.size();
        appAuthDataMap.clear();
        evictAllL2();
        LOG.info("[GW-Sign] refresh 全量清空数据源（prevSize={}）与 L2 缓存，等待 admin 逐条重推", prevSize);
    }

    // ---------------------------------------------------------------------
    // 内部方法
    // ---------------------------------------------------------------------

    /** 失效 L2 中指定 appKey（copy-on-write 整体发布） */
    private void evictL2(final String appKey) {
        if (!publicKeyMap.containsKey(appKey)) {
            return;
        }
        final Map<String, PublicKey> nextMap = new HashMap<>(publicKeyMap);
        nextMap.remove(appKey);
        publicKeyMap = Collections.unmodifiableMap(nextMap);
    }

    /** 清空整个 L2 */
    private void evictAllL2() {
        publicKeyMap = Collections.emptyMap();
    }

    // ---------------------------------------------------------------------
    // 供 AppAuthHealthIndicator 使用
    // ---------------------------------------------------------------------

    /**
     * 是否已完成首次同步（曾收到过非空数据）。
     *
     * <p>用于 HealthIndicator。注意：返回 true 不代表当前数据源非空（refresh 窗口内可能为空），
     * 只代表"曾同步过"。这是为了避免 refresh 清空瞬间 HealthIndicator 误报 DOWN。
     */
    public boolean isEverSynced() {
        return everSynced;
    }

    /**
     * 当前数据源是否为空（仅用于诊断/测试）。
     *
     * <p>HealthIndicator 不应直接用此方法判断就绪（见 {@link #everSynced} 说明）。
     */
    public boolean isCacheEmpty() {
        return appAuthDataMap.isEmpty();
    }
}
