# 实现 plan：erpm-pay-center 通过 admin 推送公钥（v3 极简版）

> 文档版本：v3.0（极简架构版，替代 v1/v2）
> 创建日期：2026-07-20
> 核心变化：放弃 v2 的"SPI HTTP Controller + sign_pub_key 新表"方案，改为**复用 ShenYu 原生 admin PUT /plugin + websocket 推送通道**，零新表、零镜像改动、零 SPI 重大重构
> 关联代码基准：`shenyu-client-java` 仓库（项目尚未上线，从头写）+ `erpm-pay-center` 仓库 `feature/shenyu-register` 分支

---

## 0. v3 设计哲学

### 0.1 一句话总结

**让 erpm-pay-center 调用 ShenYu admin 原生的 `PUT /plugin/{id}` 接口，把公钥塞进 springCloud 插件的 config JSON（字段 `gw.springcloud.app-key.{appKey}`），admin 通过 websocket 自动推送给所有 bootstrap 实例，SPI 复用现有的 `syncAdminConfig()` 机制读取并写入 cacheMap。**

### 0.2 范围边界声明

> ⚠️ **v3 范围边界**
>
> 本方案仅覆盖**公钥**的推送、同步、网关验签侧的读取与缓存。
>
> **不覆盖**（由 pay 端独立负责）：
> - RSA 密钥对的生成（建议 `KeyPairGenerator`，2048 位）
> - **私钥的存储**（强烈建议接 KMS / Vault，不要明文存 DB）
> - 私钥的下发给业务下游系统
> - 私钥的轮换触发策略（定期 / 应急）
> - 私钥泄露的应急响应
>
> 这些事项不在 v3 文档范围内，由 erpm-pay-center 团队另行设计。

### 0.3 v1 → v2 → v3 演进

| 维度 | v1（错误假设） | v2（中间版） | **v3（最终）** |
|------|---------|-----------|------------|
| 数据库 | 新建 sign_pub_key 表 | 新建表 + 专用账号 | **零新表**（复用 admin.plugin） |
| 推送通道 | SPI HTTP Controller | SPI HTTP Controller | **admin PUT /plugin**（原生） |
| 同步机制 | pay 直推 | pay 直推 + DB scheduler | **admin websocket**（原生） |
| SPI 改动 | 新增 8 个类 | 新增 9 个类 | **小改造**（升主源） |
| admin 改动 | 不动 | 不动 | **不动** |
| bootstrap 改动 | 不动 | 不动 | **不动** |
| SPI jar 体积 | ~5MB | ~5MB | **~21KB（无变化）** |
| 工作量 | ~3.5 人日 | ~5 人日 | **~1.5 人日** |
| 容量上限 | 无限（DB） | 无限（DB） | **~50 appKey**（plugin.config TEXT 65535 字节） |

### 0.4 v3 决策清单（8 项已对齐）

| # | 决策 | 选择 | 依据 |
|---|------|------|------|
| 1 | app_auth 表是否使用 | **完全不用** | 项目用 RSA 验签，与原生 HMAC 体系无关 |
| 2 | 推送通道 | **复用 admin PUT /plugin** | 零新表、零镜像改动、复用 websocket |
| 3 | admin REST API 是否可用 | **可用** | 实测：`GET /platform/login?userName=admin&password=1qaz!QAZ`，token 24h |
| 4 | PEM 塞 plugin.config 容量 | **可接受** | 单 PEM 460B，软上限 50 appKey，硬上限 ~130 |
| 5 | SPI 改造范围 | **adminConfiguredPemMap 升为主源** | 直接写 cacheMap，删除 HTTP 拉模式 |
| 6 | 并发控制 | **pay 端加锁** | GET-Modify-PUT 串行，避免丢失更新 |
| 7 | token 管理 | **缓存 + 失效重试** | 23h 提前刷新，遇 401 重新登录 |
| 8 | 整体架构 | **pay → admin → websocket → bootstrap → SPI** | 完全复用 ShenYu 原生机制 |

---

## 1. 方案总览

### 1.1 架构图（端到端）

```
                          erpm-pay-center (pay)
                                │
                  ┌─────────────┼─────────────┐
                  │             │             │
            AdminTokenMgr   AdminPluginClient  ShenyuKeyPushService
            (缓存 token,    (GET /plugin,    (加锁, GET-Modify-PUT
             失效重试)       PUT /plugin)     /plugin/8)
                                │
                                │ HTTP（带 X-Access-Token）
                                ▼
                       ShenYu admin (9095)
                       PUT /plugin/8  (springCloud)
                       全量替换 config JSON：
                         {"gw.springcloud.app-key.XXX": "<PEM>", ...}
                                │
                                │ Spring 容器事件 → DataChangedEvent
                                ▼
                       admin MySQL plugin.config（TEXT 65535 字节）
                                │
                                │ WebsocketDataChangedListener
                                ▼
                       admin websocket 推送给所有 bootstrap 实例
                                │
                                ▼
                       每个 bootstrap 实例
                       BaseDataCache.obtainPluginData("springCloud")
                                │
                                │ SPI 守护线程（gw-springcloud-http-refresh）
                                │ syncAdminConfig() line 229-240 读取
                                │ "gw.springcloud.app-key.{appKey}" 字段
                                ▼
                       【v3 核心改造点】
                       adminConfiguredPemMap 升级为主源
                       → 解析 PEM 直接写入 cacheMap
                                │
                                ▼
                       PayRsaSignService.currentKey(appKey)
                       热路径零 I/O 验签
```

### 1.2 关键特征

- ✅ **admin 完全不动**（stock 镜像 `apache/shenyu-admin:2.6.1`）
- ✅ **bootstrap 镜像完全不动**（stock 镜像）
- ✅ **admin DB 零新表**（复用 `plugin.config` 字段）
- ✅ **零新依赖**（SPI jar 体积不变，仍是 21KB）
- ✅ **复用原生 websocket 同步**（推送后秒级生效到所有实例）
- ✅ **SPI 改造极小**（仅修改 `syncAdminConfig` 中 PEM 处理逻辑 + 删除 HTTP 拉模式）
- ⚠️ **容量限制**：plugin.config TEXT 65535 字节，软上限 ~50 appKey
- ⚠️ **并发约束**：pay 端必须串行调用（GET-Modify-PUT）

---

## 2. 实测事实（已通过 curl 验证）

### 2.1 admin REST API 完整可用

| 项 | 实测结果 |
|----|---------|
| 登录端点 | `GET /platform/login?userName=admin&password=1qaz!QAZ` |
| 密码 hash 算法 | SHA-512（128 字符 hex） |
| 密码 | `1qaz!QAZ`（hash 实测匹配：`d367b642015eb195...`） |
| Token 格式 | JWT（如 `eyJ0eXAiOiJKV1Q...`） |
| Token 有效期 | **86400000ms = 24 小时** |
| 鉴权 Header | `X-Access-Token: <JWT>` |
| 实测调用 | `GET /plugin?name=springCloud` 返回 `{code:200, data:{...}}` ✅ |

**⚠️ 重要：参数名是 `userName`（驼峰），不是 `username`**。项目 MEMORY 里记录的"401 token is error"就是因为参数名错了。

### 2.2 admin.plugin 表 springCloud 记录

```sql
SELECT id, name, role, config, enabled FROM plugin WHERE name='springCloud';
-- id=8, role=Proxy, enabled=1, sort=200
-- config（600 字节，TEXT 类型，上限 65535）：
-- {
--   "gw.springcloud.key-source":"http",
--   "gw.springcloud.classpath-public-key":"biz-public-key.pem",
--   "gw.springcloud.http.base-url":"http://10.19.236.150:8470",
--   "gw.springcloud.http.path-pattern":"/sign/public-key/%s",
--   "gw.springcloud.http.connect-timeout-ms":"1000",
--   "gw.springcloud.http.read-timeout-ms":"2000",
--   "gw.springcloud.http.max-connections":"20",
--   "gw.springcloud.http.refresh-interval-seconds":"15",
--   "gw.springcloud.cache.ttl-seconds":"30",
--   "gw.springcloud.cache.failure-retry-seconds":"3",
--   "gw.springcloud.cache.allow-stale-on-refresh-failure":"true",
--   "gw.springcloud.pre-warm-app-keys":"biz001,biz002"
-- }
```

### 2.3 容量计算

| 项 | 字节数 |
|----|-------|
| `plugin.config` 字段容量上限（TEXT） | 65535 |
| 当前 springCloud config 长度 | 600 |
| 单条 2048-bit RSA 公钥 PEM | 460 |
| 单条 JSON key-value（`"gw.springcloud.app-key.XXX":"<PEM>"`） | ~500 |
| **可容纳 appKey 数（硬上限）** | ~130 |
| **可容纳 appKey 数（软上限）** | **~50**（推荐运维上限） |

### 2.4 现有 SPI 已支持读取

`shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/HttpBizPublicKeyProvider.java` 第 229-240 行：

```java
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
```

**这条路径已经通了**。v3 只需把 `adminConfiguredPemMap.put(...)` 改为 `cacheMap.put(appKey, parsePem(pem))`，让 admin 下发的公钥成为**主源**而非"HTTP 失败兜底"。

---

## 3. SPI jar 改造（`shenyu-sign-gateway-spi` 模块）

### 3.1 改造点概览

| 文件 | 动作 | 说明 |
|------|------|------|
| `HttpBizPublicKeyProvider.java` | **重命名 + 改造** | 重命名为 `AdminConfigBizPublicKeyProvider`，删除 HTTP 拉模式，admin 配置升为主源 |
| `PayRsaSignConfiguration.java` | **修改** | 调整 Bean 配置（不再传 HTTP 相关参数） |
| `pom.xml` | **不改动** | 零新依赖 |
| `BizPublicKeyProvider.java` | **保留** | 接口不变 |
| `PayRsaSignService.java` | **保留** | 无需改动 |
| `META-INF/spring.factories` | **修改** | 类名引用更新 |
| `biz-public-key.pem` | **保留** | classpath 兜底（admin 完全空时） |

### 3.2 新类 `AdminConfigBizPublicKeyProvider`（替代 `HttpBizPublicKeyProvider`）

**核心改造逻辑**：

```java
public final class AdminConfigBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdminConfigBizPublicKeyProvider.class);

    private final String classpathLocation;
    private final long cacheTtlMillis;

    /** Per-appKey 缓存（主源，来自 admin plugin.config） */
    private final ConcurrentHashMap<String, CachedEntry> cacheMap = new ConcurrentHashMap<>();

    /** 构造期预加载的 classpath 兜底公钥（admin 完全空时使用） */
    private final PublicKey classpathFallbackKey;

    /** 已知 appKey 集合（仅供日志/调试） */
    private final Set<String> knownAppKeys = new CopyOnWriteArraySet<>();

    /** 后台守护线程：周期从 BaseDataCache 同步 admin 配置（替代原 refreshScheduler） */
    private final ScheduledExecutorService refreshScheduler;

    AdminConfigBizPublicKeyProvider(String classpathLocation, long cacheTtlSeconds, long refreshIntervalSeconds) {
        this.classpathLocation = classpathLocation;
        this.cacheTtlMillis = Math.max(1L, cacheTtlSeconds) * 1000L;
        this.classpathFallbackKey = loadClasspathKey();

        // 后台守护线程：周期从 BaseDataCache 读 admin 配置（BaseDataCache 已由 websocket 自动更新）
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gw-sign-adminconfig-refresh");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(5L, refreshIntervalSeconds);
        // 首次延迟 5s 让 BaseDataCache 完成首次 websocket 同步，之后周期执行
        this.refreshScheduler.scheduleWithFixedDelay(this::syncFromBaseDataCache, 5, interval, TimeUnit.SECONDS);
        LOG.info("[GW-Sign] 公钥源已启用 AdminConfig 模式 refresh={}s ttl={}s", interval, cacheTtlSeconds);
    }

    /**
     * 热路径：获取指定 appKey 的验签公钥。
     * 零 I/O、零锁，仅读 ConcurrentHashMap。
     */
    @Override
    public PublicKey currentKey(String appKey) throws Exception {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("appKey must not be empty");
        }
        String key = appKey.trim();
        knownAppKeys.add(key);

        CachedEntry cached = cacheMap.get(key);
        if (cached != null) {
            return cached.publicKey;   // fresh 或 stale 均立即返回
        }
        if (classpathFallbackKey != null) {
            LOG.debug("[GW-Sign] cacheMap miss appKey={}，临时用 classpath 兜底（scheduler 将异步刷新）", key);
            return classpathFallbackKey;
        }
        throw new IllegalStateException("no cached key for appKey=" + key + " and no classpath fallback");
    }

    /**
     * 后台守护线程：从 BaseDataCache 读 admin plugin.config，解析 gw.springcloud.app-key.{appKey} 字段。
     * BaseDataCache 已由 ShenYu 原生 websocket 同步自动维护，本方法只读不拉 HTTP。
     */
    private void syncFromBaseDataCache() {
        try {
            PluginData pd = BaseDataCache.getInstance().obtainPluginData("springCloud");
            String json = pd == null ? null : pd.getConfig();
            if (json == null || json.trim().isEmpty()) {
                return;
            }
            JsonObject cfg = JsonParser.parseString(json).getAsJsonObject();

            // 读 per-appKey 公钥（主源）：key 形如 gw.springcloud.app-key.{appKey}
            int refreshedCount = 0;
            for (String key : cfg.keySet()) {
                if (!key.startsWith("gw.springcloud.app-key.")) {
                    continue;
                }
                String appKey = key.substring("gw.springcloud.app-key.".length());
                if (appKey.isEmpty()) continue;
                String pem = cfg.get(key).getAsString();
                if (pem == null || pem.trim().isEmpty()) continue;
                try {
                    PublicKey parsed = parsePem(pem.trim());
                    cacheMap.put(appKey, new CachedEntry(parsed, System.currentTimeMillis() + cacheTtlMillis));
                    knownAppKeys.add(appKey);
                    refreshedCount++;
                } catch (Exception e) {
                    LOG.warn("[GW-Sign] 解析 admin 下发公钥失败 appKey={}: {}", appKey, e.getMessage());
                }
            }
            if (refreshedCount > 0) {
                LOG.info("[GW-Sign] 从 admin plugin.config 同步 {} 条公钥", refreshedCount);
            }

            // 兼容现有 pre-warm-app-keys（保留原行为）
            if (cfg.has("gw.springcloud.pre-warm-app-keys")) {
                String pw = cfg.get("gw.springcloud.pre-warm-app-keys").getAsString();
                if (pw != null) {
                    for (String k : pw.split(",")) {
                        String t = k.trim();
                        if (!t.isEmpty()) knownAppKeys.add(t);
                    }
                }
            }

            // 兼容现有 cache TTL 等配置热更新
            if (cfg.has("gw.springcloud.cache.ttl-seconds")) {
                this.cacheTtlMillis = Math.max(1L, cfg.get("gw.springcloud.cache.ttl-seconds").getAsLong()) * 1000L;
                // ⚠️ 这要求 cacheTtlMillis 不是 final，需把字段改为 volatile
            }
        } catch (Exception e) {
            LOG.debug("[GW-Sign] syncFromBaseDataCache 跳过（BaseDataCache 未就绪或 JSON 格式错误）", e);
        }
    }

    @Override
    public void close() {
        if (refreshScheduler != null) {
            try {
                refreshScheduler.shutdownNow();
            } catch (Exception ignored) {}
        }
    }

    /** PEM → PublicKey（公开静态工具方法） */
    public static PublicKey parsePem(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 计算 fingerprint（PEM → DER → SHA-256） */
    public static String fingerprintOf(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public int loadedKeyCount() { return cacheMap.size(); }
    public Set<String> knownAppKeys() { return Collections.unmodifiableSet(knownAppKeys); }

    private PublicKey loadClasspathKey() {
        // 与原 HttpBizPublicKeyProvider.loadClasspathKey 相同实现
    }

    /** 缓存条目 */
    private static final class CachedEntry {
        final PublicKey publicKey;
        final long expireAtMillis;
        CachedEntry(PublicKey publicKey, long expireAtMillis) {
            this.publicKey = publicKey;
            this.expireAtMillis = expireAtMillis;
        }
    }
}
```

### 3.3 改造 `PayRsaSignConfiguration`

```java
@Configuration
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    @Bean
    public AdminConfigBizPublicKeyProvider bizPublicKeyProvider(Environment env) {
        String classpathLocation = env.getProperty(
            "gw.springcloud.classpath-public-key", "biz-public-key.pem");
        long cacheTtlSeconds = getLong(env, "gw.springcloud.cache.ttl-seconds", 86400L);  // 24h
        long refreshIntervalSeconds = getLong(env, "gw.springcloud.refresh-interval-seconds", 30L);  // 30s
        return new AdminConfigBizPublicKeyProvider(
            classpathLocation, cacheTtlSeconds, refreshIntervalSeconds);
    }

    @Bean
    public SignService signService(AdminConfigBizPublicKeyProvider provider) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册，公钥源 = admin plugin.config (gw.springcloud.app-key.*)");
        return new PayRsaSignService(provider);
    }

    // 保留 getInt/getLong/getString 辅助方法
}
```

### 3.4 修改 `META-INF/spring.factories`

```
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.apache.shenyu.plugin.sign.custom.PayRsaSignConfiguration
```

（不变，因为 `PayRsaSignConfiguration` 类名没变）

### 3.5 SPI 改造影响清单

| 删除 | 修改 | 保留 |
|------|------|------|
| `fetchPem(appKey)` HTTP 拉方法 | `syncAdminConfig` → `syncFromBaseDataCache`（去 HTTP） | `currentKey` 热路径 |
| `httpClient` Apache HttpClient | `adminConfiguredPemMap.put` → `cacheMap.put` | `classpathFallbackKey` 兜底 |
| `refreshAll` HTTP 拉模式循环 | `refreshScheduler` 改为周期读 BaseDataCache | `BizPublicKeyProvider` 接口 |
| `swapHttpClient` | `cacheTtlMillis` 改 volatile | `PayRsaSignService` |
| `notFoundAppKeys` | `knownAppKeys` 仅做日志 | `biz-public-key.pem` |
| `sourceAvailable` | | `parsePem` / `fingerprintOf` |

---

## 4. admin 配置层（springCloud 插件 config）

### 4.1 plugin.config 字段约定（v3 新增字段）

新增 `gw.springcloud.app-key.{appKey}` 字段（每个 appKey 一个 key），value 是完整 PEM 字符串。

**示例**（推送 YYT 和 SYD 两个 appKey 后）：

```json
{
  "gw.springcloud.key-source":"admin",
  "gw.springcloud.classpath-public-key":"biz-public-key.pem",
  "gw.springcloud.cache.ttl-seconds":"86400",
  "gw.springcloud.refresh-interval-seconds":"30",
  "gw.springcloud.pre-warm-app-keys":"YYT,SYD",

  "gw.springcloud.app-key.YYT":"-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAu...\n-----END PUBLIC KEY-----",

  "gw.springcloud.app-key.SYD":"-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAy...\n-----END PUBLIC KEY-----"
}
```

**注意**：
- `gw.springcloud.key-source` 改为 `"admin"`（语义标识，SPI 不再依赖此值做分支，但保留以便回滚到 http 模式时识别）
- 移除 `gw.springcloud.http.*` 系列（HTTP 拉模式废弃）
- PEM 中的 `\n` 必须保留为 JSON 转义形式 `\\n`（Gson 解析时会还原）

### 4.2 plugin_handle 配置（admin UI 可编辑）

`docs/sign-spi-admin-config.sql` 需要补充：保留现有 `gw.springcloud.app-key.biz001/biz002` 字段定义（plugin_handle 已有），实际使用时由 pay 端通过 `PUT /plugin/8` 动态写入 config，不再需要预先在 plugin_handle 中定义所有 appKey。

---

## 5. erpm-pay-center 改造（外部仓库 `feature/shenyu-register` 分支）

### 5.1 新增类清单

```
com.jzt.erpm.pay.shenyu
├── AdminTokenManager.java           # token 缓存与失效重试
├── AdminPluginClient.java           # 封装 GET/PUT /plugin
├── ShenyuKeyPushService.java        # 业务入口（加锁 + GET-Modify-PUT）
├── AdminKeyPushProperties.java      # @ConfigurationProperties
└── dto
    ├── PluginDTO.java
    └── PushResult.java
```

### 5.2 `AdminTokenManager`（token 缓存 + 失效重试）

```java
@Slf4j
@Component
public class AdminTokenManager {

    private final RestTemplate restTemplate;
    private final AdminKeyPushProperties props;

    private volatile String cachedToken;
    private volatile long expireAt;  // epoch millis

    public String getToken() {
        // 提前 1 小时刷新，避免临界过期
        if (cachedToken == null || System.currentTimeMillis() > expireAt - 3600_000L) {
            refreshToken();
        }
        return cachedToken;
    }

    /** 遇 401 时调用，强制下次重新登录 */
    public void invalidate() {
        cachedToken = null;
    }

    private synchronized void refreshToken() {
        try {
            String url = props.getAdminBaseUrl() + "/platform/login"
                + "?userName=" + URLEncoder.encode(props.getAdminUsername(), "UTF-8")
                + "&password=" + URLEncoder.encode(props.getAdminPassword(), "UTF-8");

            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                url, HttpMethod.GET, null, JsonNode.class);

            JsonNode body = resp.getBody();
            if (body == null || body.path("code").asInt() != 200) {
                throw new RuntimeException("admin login failed: " + resp.getBody());
            }

            JsonNode data = body.path("data");
            this.cachedToken = data.path("token").asText();
            this.expireAt = System.currentTimeMillis() + data.path("expiredTime").asLong();

            log.info("[ShenyuPush] admin token 已刷新，有效期 {}h",
                (expireAt - System.currentTimeMillis()) / 3600_000L);
        } catch (Exception e) {
            log.error("[ShenyuPush] admin 登录失败", e);
            throw new RuntimeException("admin login failed", e);
        }
    }
}
```

### 5.3 `AdminPluginClient`（封装 GET/PUT）

```java
@Slf4j
@Component
public class AdminPluginClient {

    private final RestTemplate restTemplate;
    private final AdminTokenManager tokenManager;
    private final AdminKeyPushProperties props;

    /**
     * 查询 springCloud 插件完整 config。
     * 失败时自动重试一次（token 过期场景）。
     */
    public PluginDTO getSpringCloudPlugin() {
        return getPluginById(props.getSpringCloudPluginId());
    }

    public PluginDTO getPluginById(String pluginId) {
        return executeWithRetry(token -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Access-Token", token);
            headers.set("Accept", "application/json");

            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                props.getAdminBaseUrl() + "/plugin/" + pluginId,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = resp.getBody();
            if (body == null || body.path("code").asInt() != 200) {
                throw new RuntimeException("getPlugin failed: " + resp.getBody());
            }

            JsonNode data = body.path("data");
            PluginDTO dto = new PluginDTO();
            dto.setId(data.path("id").asText());
            dto.setName(data.path("name").asText());
            dto.setRole(data.path("role").asText());
            dto.setConfig(data.path("config").asText());
            dto.setEnabled(data.path("enabled").asBoolean());
            dto.setSort(data.path("sort").asInt());
            return dto;
        });
    }

    /**
     * 更新 springCloud 插件 config。
     * 全量替换语义（PUT /plugin/{id}），所以必须先 GET 拿完整对象。
     */
    public void updatePlugin(PluginDTO dto) {
        executeWithRetry(token -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Access-Token", token);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);  // ⚠️ @ModelAttribute 要求 form

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("id", dto.getId());
            form.add("name", dto.getName());
            form.add("role", dto.getRole());
            form.add("config", dto.getConfig());
            form.add("enabled", String.valueOf(dto.getEnabled()));
            form.add("sort", String.valueOf(dto.getSort()));

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                props.getAdminBaseUrl() + "/plugin/" + dto.getId(),
                HttpMethod.PUT, entity, JsonNode.class);

            JsonNode body = resp.getBody();
            if (body == null || body.path("code").asInt() != 200) {
                throw new RuntimeException("updatePlugin failed: " + resp.getBody());
            }
            return null;
        });
    }

    /**
     * 带 401 自动重试的执行模板。
     */
    private <T> T executeWithRetry(TokenAction<T> action) {
        String token = tokenManager.getToken();
        try {
            return action.execute(token);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("[ShenyuPush] admin 返回 401，token 可能过期，重新登录后重试");
            tokenManager.invalidate();
            return action.execute(tokenManager.getToken());
        }
    }

    @FunctionalInterface
    private interface TokenAction<T> {
        T execute(String token);
    }
}
```

### 5.4 `ShenyuKeyPushService`（业务入口，加锁）

```java
@Slf4j
@Service
public class ShenyuKeyPushService {

    /** 全局串行锁：保证 GET-Modify-PUT 原子性，避免并发丢失更新 */
    private final Lock globalLock = new ReentrantLock();

    private final AdminPluginClient pluginClient;
    private final AdminKeyPushProperties props;

    /**
     * 推送一个 appKey 的公钥到 ShenYu admin。
     *
     * 内部流程（持锁）：
     *   1. GET /plugin/{id} 拿当前完整 springCloud config
     *   2. 解析 JSON，追加/覆盖 gw.springcloud.app-key.{appKey} = pem
     *   3. PUT /plugin/{id} 提交完整 config
     *   4. admin 自动 websocket 推送到所有 bootstrap
     *
     * @param appKey 业务方标识（如 YYT）
     * @param pem    完整 PEM 公钥（含 BEGIN/END 标记）
     * @return PushResult，含 admin 返回信息和 fingerprint
     */
    public PushResult pushPublicKey(String appKey, String pem) {
        Assert.hasText(appKey, "appKey must not be empty");
        Assert.hasText(pem, "pem must not be empty");

        String fingerprint;
        try {
            fingerprint = computeFingerprint(pem);
        } catch (Exception e) {
            return PushResult.invalidPem(e.getMessage());
        }

        globalLock.lock();
        try {
            // 1. GET 当前完整 config
            PluginDTO plugin = pluginClient.getSpringCloudPlugin();
            String oldConfig = plugin.getConfig();
            JsonObject cfg;
            try {
                cfg = oldConfig == null || oldConfig.trim().isEmpty()
                    ? new JsonObject()
                    : JsonParser.parseString(oldConfig).getAsJsonObject();
            } catch (Exception e) {
                log.error("[ShenyuPush] springCloud config 解析失败: {}", oldConfig);
                return PushResult.failure("config_parse_error", e.getMessage());
            }

            // 2. 追加/覆盖 appKey
            String configKey = "gw.springcloud.app-key." + appKey;
            cfg.addProperty(configKey, pem);

            // 3. 同步更新 pre-warm-app-keys（保证 SPI 启动期预热）
            String preWarmKey = "gw.springcloud.pre-warm-app-keys";
            Set<String> preWarmSet = new LinkedHashSet<>();
            if (cfg.has(preWarmKey) && !cfg.get(preWarmKey).isJsonNull()) {
                String preWarm = cfg.get(preWarmKey).getAsString();
                if (preWarm != null && !preWarm.isEmpty()) {
                    preWarmSet.addAll(Arrays.asList(preWarm.split(",")));
                }
            }
            preWarmSet.remove("");
            preWarmSet.add(appKey);
            cfg.addProperty(preWarmKey, String.join(",", preWarmSet));

            // 4. PUT 完整 config
            plugin.setConfig(cfg.toString());
            pluginClient.updatePlugin(plugin);

            log.info("[ShenyuPush] 推送公钥成功 appKey={} fingerprint={}", appKey, fingerprint);
            return PushResult.success(appKey, fingerprint);

        } catch (Exception e) {
            log.error("[ShenyuPush] 推送公钥失败 appKey={}", appKey, e);
            return PushResult.failure("push_failed", e.getMessage());
        } finally {
            globalLock.unlock();
        }
    }

    /**
     * 删除一个 appKey 的公钥（软删除：从 config JSON 移除该字段）。
     */
    public PushResult deletePublicKey(String appKey) {
        globalLock.lock();
        try {
            PluginDTO plugin = pluginClient.getSpringCloudPlugin();
            JsonObject cfg = JsonParser.parseString(plugin.getConfig()).getAsJsonObject();
            cfg.remove("gw.springcloud.app-key." + appKey);
            plugin.setConfig(cfg.toString());
            pluginClient.updatePlugin(plugin);
            return PushResult.success(appKey, null);
        } catch (Exception e) {
            log.error("[ShenyuPush] 删除公钥失败 appKey={}", appKey, e);
            return PushResult.failure("delete_failed", e.getMessage());
        } finally {
            globalLock.unlock();
        }
    }

    /**
     * 查询所有已推送的 appKey 列表（运维辅助）。
     */
    public Set<String> listAppKeys() {
        PluginDTO plugin = pluginClient.getSpringCloudPlugin();
        JsonObject cfg = JsonParser.parseString(plugin.getConfig()).getAsJsonObject();
        Set<String> appKeys = new TreeSet<>();
        for (String key : cfg.keySet()) {
            if (key.startsWith("gw.springcloud.app-key.")) {
                appKeys.add(key.substring("gw.springcloud.app-key.".length()));
            }
        }
        return appKeys;
    }

    private String computeFingerprint(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

### 5.5 `AdminKeyPushProperties`（配置类）

```java
@Data
@Component
@ConfigurationProperties(prefix = "shenyu.push")
public class AdminKeyPushProperties {
    /** admin 基础地址 */
    private String adminBaseUrl = "http://localhost:9096";
    /** admin 用户名 */
    private String adminUsername = "admin";
    /** admin 密码（明文，部署时通过环境变量或加密配置注入） */
    private String adminPassword;
    /** springCloud 插件 id（默认 8） */
    private String springCloudPluginId = "8";
}
```

### 5.6 配置文件（application.yml）

```yaml
shenyu:
  push:
    admin-base-url: http://10.19.236.150:9096   # admin 容器宿主机映射端口
    admin-username: admin
    admin-password: ${SHENYU_ADMIN_PASSWORD}     # 通过环境变量注入，避免明文
    spring-cloud-plugin-id: 8
```

### 5.7 业务触发入口（可选）

```java
@RestController
@RequestMapping("/api/v1/sign-keys")
public class KeyRotationController {

    private final PayAppConfigService payAppConfigService;
    private final ShenyuKeyPushService shenyuKeyPushService;

    /**
     * 触发密钥轮换：业务内部生成新密钥对 → 保存 pay DB → 推送给所有 shenyu 网关。
     * 注意：推送成功后 websocket 通常秒级生效，运维可立即查 admin UI 确认。
     */
    @PostMapping("/{appKey}/rotate-and-push")
    public R<RotateVO> rotateAndPush(@PathVariable String appKey) {
        // 1. 生成新密钥对（私钥存 KMS，公钥存 pay DB）
        RotateResult r = payAppConfigService.rotateKeyPair(appKey);
        String newPem = r.getPublicKeyPem();

        // 2. 推送到 admin（admin → websocket → 所有 bootstrap）
        PushResult pushResult = shenyuKeyPushService.pushPublicKey(appKey, newPem);

        return R.success(new RotateVO(appKey, r.getFingerprint(), pushResult));
    }
}
```

---

## 6. 文件改动清单

### 6.1 `shenyu-client-java` 仓库（本仓库）

| 文件 | 动作 | 说明 |
|------|------|------|
| `docs/sign-pub-key-push-实现方案.md` | **已更新（v3）** | 本文档 |
| `shenyu-sign-gateway-spi/src/main/java/.../HttpBizPublicKeyProvider.java` | **删除** | HTTP 拉模式整体废弃 |
| `shenyu-sign-gateway-spi/src/main/java/.../AdminConfigBizPublicKeyProvider.java` | **新建** | DB 模式核心类 |
| `shenyu-sign-gateway-spi/src/main/java/.../PayRsaSignConfiguration.java` | **修改** | Bean 配置简化（去掉 HTTP 参数） |
| `shenyu-sign-gateway-spi/pom.xml` | **不改动** | 零新依赖 |
| `shenyu-sign-gateway-spi/src/main/resources/META-INF/spring.factories` | **不改动** | 类名引用未变 |
| `shenyu-sign-gateway-spi/src/main/resources/biz-public-key.pem` | **保留** | classpath 兜底 |
| `shenyu-sign-gateway-spi/docker-compose-shenyu-bootstrap-patch.yaml` | **修改** | env 调整：去掉 GW_SPRINGCLOUD_HTTP_*，保留 KEY_SOURCE=admin |
| `shenyu-sign-gateway-spi/src/test/java/...` | **修改** | 单测更新（移除 HTTP mock） |

**SPI jar 体积**：保持 21KB（零新依赖）

### 6.2 `erpm-pay-center` 仓库（外部仓库，由其团队实施）

| 文件 | 动作 | 说明 |
|------|------|------|
| `pay-host/.../shenyu/AdminTokenManager.java` | 新建 | token 缓存 + 失效重试 |
| `pay-host/.../shenyu/AdminPluginClient.java` | 新建 | GET/PUT /plugin 封装 |
| `pay-host/.../shenyu/ShenyuKeyPushService.java` | 新建 | 业务入口（加锁） |
| `pay-host/.../shenyu/AdminKeyPushProperties.java` | 新建 | 配置类 |
| `pay-host/.../shenyu/dto/PluginDTO.java` | 新建 | DTO |
| `pay-host/.../shenyu/dto/PushResult.java` | 新建 | 响应 DTO |
| `pay-host/src/main/resources/application.yml` | 修改 | 加 `shenyu.push.*` |
| `pay-host/.../controller/KeyRotationController.java` | 可选新建 | 业务触发入口 |

### 6.3 admin 配置变更

| 文件 | 动作 |
|------|------|
| `docs/sign-spi-admin-config.sql` | 不需要新 plugin_handle（appKey 列表由 pay 动态写入 config，不预定义） |

---

## 7. 关键风险与对策

| 风险 | 对策 |
|------|------|
| **plugin.config 容量爆掉** | 软上限 50 appKey，硬上限 ~130；超过时需重新设计 |
| **pay 端并发推送导致丢失更新** | `ShenyuKeyPushService` 内 `ReentrantLock` 全局串行 |
| **admin token 过期** | `AdminTokenManager` 提前 1h 刷新，遇 401 重新登录重试 |
| **admin DB / websocket 故障** | BaseDataCache 仍持上一次推送的 config，SPI 缓存继续可用；下次 admin 恢复后自动同步 |
| **bootstrap 重启时 admin 未就绪** | BaseDataCache 启动时为空，SPI cacheMap 也为空 → 降级到 classpath 兜底公钥（已有机制） |
| **PEM 格式错误** | SPI `parsePem()` 解析失败时 `LOG.warn` 跳过该 appKey；pay 端推送前可先用 SPI 工具方法校验 |
| **PEM 中的 `\n` 在 JSON 转义** | Gson/JsonParser 自动处理 `\\n` ↔ `\n`，无需手动处理 |
| **appKey 命名冲突** | appKey 在 plugin.config JSON 里是 key，必须是合法 JSON key（不含特殊字符如 `.`、`"`） |
| **多 admin 实例 / 集群** | 当前单 admin 实例；未来若集群化需保证 PUT /plugin 的强一致性 |

---

## 8. 验证步骤

### 8.1 SPI 改造验证（启动期）

部署新 SPI jar 后重启 bootstrap，日志应出现：

```
[GW-Sign] 公钥源已启用 AdminConfig 模式 refresh=30s ttl=86400s
[GW-Sign] 从 admin plugin.config 同步 0 条公钥    # 首次启动，admin 还没推送任何公钥
[GW-Sign] PayRsaSignService 已注册，公钥源 = admin plugin.config (gw.springcloud.app-key.*)
```

### 8.2 手动推送一次公钥（不依赖 pay 端，直接 SQL/curl）

```bash
# 方案 A：直接 SQL 改 plugin.config（最快验证）
docker exec mysql57 mysql -uroot -proot shenyu_261 -e "
UPDATE plugin 
SET config = JSON_SET(config, '\$.\"gw.springcloud.app-key.YYT\"', '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAu...\n-----END PUBLIC KEY-----')
WHERE name = 'springCloud';"

# 30s 内 bootstrap 日志应出现：
# [GW-Sign] 从 admin plugin.config 同步 1 条公钥
```

### 8.3 通过 admin REST API 推送（验证 pay 端方案）

```bash
# 1. 登录拿 token
TOKEN=$(curl -s "http://localhost:9096/platform/login?userName=admin&password=1qaz!QAZ" \
  | python -c "import sys, json; print(json.load(sys.stdin)['data']['token'])")
echo "Token: ${TOKEN:0:50}..."

# 2. GET 当前 plugin config
curl -s "http://localhost:9096/plugin/8" -H "X-Access-Token: $TOKEN" | python -m json.tool | head -20

# 3. PUT 更新 config（追加 gw.springcloud.app-key.YYT）
# 略，pay 端 ShenyuKeyPushService 已封装此逻辑
```

### 8.4 admin UI 验证（最直观）

打开 `http://localhost:9096`，登录后进入：
- **基础配置 → 插件管理 → springCloud → 编辑**
- config 输入框应能看到 `gw.springcloud.app-key.YYT` 字段（如 admin UI 渲染了 plugin_handle）

### 8.5 端到端验签

业务方用 YYT 私钥加签 → 网关用 admin 下发的 YYT 公钥验签 → HTTP 200

### 8.6 多实例同步验证

- 推送后立即查两个 bootstrap 实例的 `/actuator/health`（确保在线）
- 任选一个实例发起验签请求，应 200
- 另一个实例发起验签请求，也应 200（websocket 秒级同步）

### 8.7 容量上限验证

- 构造 60 个 appKey 推送，监控 plugin.config 总长度
- 超过 60000 字节时告警，避免接近 65535 硬上限

---

## 9. 工作量估算

| 模块 | 工作量 |
|------|--------|
| SPI jar 改造（删 HttpBizPublicKeyProvider + 新建 AdminConfigBizPublicKeyProvider + 改 Configuration + 单测） | **0.5 人日** |
| erpm-pay-center 客户端（5 个类 + 配置） | **0.5 人日** |
| admin 配置（无需 plugin_handle 改动） | **0 人日** |
| 端到端验证（含 curl/SQL/UI 多种方式） | **0.3 人日** |
| 文档 | **0.2 人日** |
| **合计** | **约 1.5 人日** |

---

## 10. 实施顺序

1. **SPI 改造**（0.5 人日）：
   - 新建 `AdminConfigBizPublicKeyProvider`
   - 删除 `HttpBizPublicKeyProvider`
   - 修改 `PayRsaSignConfiguration`
   - 更新单测
   - 重新打包 jar，部署到 `ext-lib`，重启 bootstrap

2. **admin 配置调整**（0 人日）：
   - 通过 SQL 或 admin UI 把 springCloud config 里的 `gw.springcloud.http.*` 字段移除（可选清理）
   - 改 `gw.springcloud.key-source` 为 `"admin"`

3. **手动推送验证**（0.2 人日）：
   - SQL 直改 + curl 双方式验证 SPI 能读到 PEM
   - 端到端验签

4. **erpm-pay-center 客户端开发**（0.5 人日，外部团队）：
   - 实现 5 个类
   - 写业务触发接口

5. **联调**（0.3 人日）：
   - pay 调 admin REST API
   - admin → websocket → bootstrap → SPI 完整链路验证

---

## 11. 决策追溯（v1 → v2 → v3 演进）

### v1 → v2：从"扩展现有 HTTP 拉模式"到"从头设计"

- **v1 错误假设**：项目已上线，需兼容现有 HTTP 拉模式
- **v2 修正**：项目未上线，从头设计；引入 sign_pub_key 表 + SPI HTTP Controller + DB scheduler

### v2 → v3：从"自建通道"到"复用原生机制"

v2 方案存在的根本问题：

1. ❌ **SPI jar 在 WebFlux 网关进程内暴露 HTTP Controller**：
   - 占用 EventLoop 线程做 DB I/O（与 SPI 设计原则冲突）
   - 推送请求可能被网关自身的 sign 插件拦截（自验签问题）
   - 违反"控制面 vs 数据面"隔离

2. ❌ **绕过 ShenYu 架构哲学**：让数据面（bootstrap）直连控制面（admin）的 DB，跳过 websocket 同步

3. ❌ **工作量大**：5 人日 + 5MB jar 膨胀 + 复杂限流/鉴权/审计

**v3 关键洞察**：现有 SPI 第 229-240 行**已经完整支持**从 admin plugin.config 读公钥，只是把它当作"HTTP 失败兜底"使用。**只需把这条路径升为主源，删除 HTTP 拉模式**即可。

### v3 关键事实核查（实测验证）

| 项 | 实测结果 |
|----|---------|
| admin REST API 是否可用 | ✅ `GET /platform/login?userName=admin&password=1qaz!QAZ` 成功返回 JWT |
| Token 有效期 | ✅ 24 小时（86400000ms） |
| `GET /plugin?name=springCloud` | ✅ 返回 `{code:200, data:{...}}` |
| plugin.config 字段类型 | ✅ TEXT 65535 字节 |
| 现有 SPI 是否已读 `gw.springcloud.app-key.*` | ✅ `HttpBizPublicKeyProvider.java` line 229-240 |
| 项目 MEMORY 记录的"401 token is error" | ❌ 已破解：参数名错（`username` vs `userName`） |

---

## 12. 后续可选增强（不在 v3 范围）

| 增强 | 价值 | 代价 |
|------|------|------|
| **admin UI 渲染 appKey 列表** | 运维可视化（看每个 appKey 的 fingerprint） | 改 shenyu-dashboard 前端 |
| **PEM 在 admin UI 显示** | 直观检查公钥内容 | 安全性差（公钥本就公开，但仍建议默认隐藏） |
| **批量推送接口** | 减少 HTTP 往返 | 一次 PUT 已含所有 appKey，pay 端循环即可 |
| **推送审计日志** | 追溯谁在什么时候改了哪个 appKey | admin 已有 operation_record_log 表，可用现有审计 |
| **appKey 删除接口** | 软删除（从 config 移除字段） | 已在 `ShenyuKeyPushService.deletePublicKey` 实现 |
| **超过 50 appKey 的扩展方案** | 应对未来增长 | 需重新设计（fork admin 加专用表 / 走 sign_pub_key 表） |

---

## 13. 参考文档与代码索引

### 13.1 本仓库相关文件

| 角色 | 路径 |
|------|------|
| 现有 SPI 主类（待删除） | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/HttpBizPublicKeyProvider.java` |
| SPI 配置装配 | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/PayRsaSignConfiguration.java` |
| SPI spring.factories | `shenyu-sign-gateway-spi/src/main/resources/META-INF/spring.factories` |
| SPI 兜底 PEM | `shenyu-sign-gateway-spi/src/main/resources/biz-public-key.pem` |
| 现有 admin 配置 SQL | `docs/sign-spi-admin-config.sql` |
| 改造计划文档 | `docs/sign-spi-http-改造计划.md` |
| 验证报告 | `docs/sign-spi-http-验证报告.md` |
| bootstrap compose 补丁 | `shenyu-sign-gateway-spi/docker-compose-shenyu-bootstrap-patch.yaml` |

### 13.2 ShenYu 2.6.1 原生相关

| 角色 | 路径（apache/shenyu 仓库） |
|------|------|
| admin PluginController | `shenyu-admin/src/main/java/.../controller/PluginController.java` |
| admin PlatformController | `shenyu-admin/src/main/java/.../controller/PlatformController.java` |
| admin WebsocketDataChangedListener | `shenyu-admin/src/main/java/.../listener/websocket/WebsocketDataChangedListener.java` |
| admin AbstractDataChangedListener | `shenyu-admin/src/main/java/.../listener/AbstractDataChangedListener.java` |
| bootstrap BaseDataCache | `shenyu-plugin-base/.../cache/BaseDataCache.java` |
| 反编译的 admin jar | `/tmp/shenyu-admin.jar`（如需） |

### 13.3 erpm-pay-center 相关

| 角色 | 路径 |
|------|------|
| 仓库根 | `D:\IdeaProjects\jzt\erpm-pay-center` |
| 基准分支 | `feature/shenyu-register` |
| 现有公钥查询接口 | `GET /api/v1/sign/public-key/{appKey}`（v3 后该接口不再被网关调用，但 pay 内部业务仍可用） |
| 公钥实体 | `PayAppConfigEntity.appPublicKey`（DB `app_public_key`） |

---

**文档结束**

> v3 文档基于 8 项已对齐决策 + 实测验证，与 v1/v2 不兼容。实施时请以 v3 为准。
> 总工作量约 **1.5 人日**（SPI 改造 0.5 + pay 客户端 0.5 + 验证 0.3 + 文档 0.2）。
