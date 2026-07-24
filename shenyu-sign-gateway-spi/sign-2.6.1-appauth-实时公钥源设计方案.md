# ShenYu 2.6.1 自定义 SPI —— 从 `app_auth` 表实时获取验签公钥（设计方案）

> 状态：**已实现，代码已落地，25 单测全绿**
> 版本：v2.0  日期：2026-07-24
> 模块：`shenyu-sign-gateway-spi`（`D:\privategit\github\shenyu-client-java\shenyu-sign-gateway-spi`）
> 授权参考：`D:\privategit\github\shenyu`（ShenYu 2.6.1 源码）
>
> **v2.0 变更（grilling 四轮拷问 + 源码核实后定稿）**：
> 1. **缓存架构重构（D1）**：`SignCacheBizPublicKeyProvider` 同时 `implements BizPublicKeyProvider, AuthDataSubscriber`，**单订阅者原则**——数据源（`ConcurrentHashMap<String,AppAuthData>`）与 L2 公钥缓存由同一对象管理，杜绝多订阅者间的 refresh 顺序竞态（lost update）。override `refresh()` 做 clear，解决 ShenYu 原生 REFRESH"逐条覆盖不删 key"导致直改库陈旧的问题。
> 2. **就绪检查（D2）**：新增 `AppAuthHealthIndicator`（actuator），判据用 `everSynced` 标志（首次收到数据后永真），**不裸用 `isEmpty()`**——避免全量 REFRESH 清空窗口的假阴性导致 K8s 误摘流。
> 3. **enabled 实时查（D3）**：L2 只存 `PublicKey`，`enabled` 每次从数据源实时读，对齐原生语义，禁用/回滚仅受 websocket 推送延迟影响。
> 4. **SignService 替换加固（P0）**：`PayRsaSignConfiguration` 加 `@AutoConfigureBefore(SignPluginConfiguration.class)`，保证先于官方注册，消除双 Bean 风险。
> 5. **7 条工程铁律**全部落地（见下文 §A）。
> 6. **新增 25 个单元测试**，全部通过。
>
> **v1.1 变更**（已被 v2.0 覆盖，保留历史）：移除旧实现 `AdminConfigBizPublicKeyProvider`，单一 `SignCacheBizPublicKeyProvider` 实现，不支持双源切换。

---

## A. 7 条工程铁律（实现必须遵守，违反即编译失败或运行时爆炸）

| # | 铁律 | 来源 |
|---|---|---|
| 1 | Java 1.8，**禁用 `var`**（Java 10+ 语法） | pom `maven.compiler.source=1.8`，bootstrap 镜像 JDK 8 |
| 2 | **禁用 `@Component`**，所有 Bean 用 `@Bean` 在 `PayRsaSignConfiguration`（spring.factories 装配）声明 | bootstrap 主类在 `org.apache.shenyu.bootstrap`，扫不到 `plugin.sign.*` 子包 |
| 3 | 统一包名 `org.apache.shenyu.plugin.sign.custom`，**禁止冒充**官方 `cache/subscriber/health` 包 | 同 classloader 下 split package 隐患 |
| 4 | **单订阅者**：`SignCacheBizPublicKeyProvider` 是唯一 `AuthDataSubscriber` 实现外的订阅者 | 多订阅者 refresh 顺序竞态（lost update） |
| 5 | `AppAuthData.getEnabled()` 返回 `Boolean`，**禁用 `!getEnabled()`**，一律 `Boolean.TRUE.equals(...)` | null 拆箱 NPE |
| 6 | enabled 实时查数据源，不缓存进 L2 | D3 |
| 7 | `@AutoConfigureBefore(SignPluginConfiguration.class)` 强制 Bean 注册顺序 | 双 SignService Bean 风险 |

---

## 0. 读者对象与阅读路径

| 角色 | 建议阅读章节 |
|---|---|
| 架构评审 | §1 背景、§2 关键事实修正、§3 方案对比、§9 风险 |
| 后端实现 | §4 总体设计、§5 类设计、§6 改动点清单、§7 数据流 |
| DBA / 运维 | §6.1 DDL 变更、§8 部署与回滚 |
| 测试 | §10 测试矩阵、§11 SOP |

---

## 1. 背景与目标

### 1.1 现状（当前 SPI 工作方式）

当前 `shenyu-sign-gateway-spi` 的 `AdminConfigBizPublicKeyProvider` 通过 **`plugin` 表**间接承载公钥：

```
erpm-pay-center PUT /plugin/{id}
  → 写 plugin(name='springCloud').config（JSON，字段 gw.springcloud.app-key.<appKey> = PEM）
  → admin websocket 下发 PLUGIN group
  → bootstrap BaseDataCache.PLUGIN_MAP
  → AdminConfigBizPublicKeyProvider 后台线程每 30s 轮询 BaseDataCache.obtainPluginData("springCloud")
  → 解析 config JSON，重建 cacheMap（volatile 原子发布）
```

- **优点**：复用了 ShenYu 原生的 PLUGIN 同步通路，无需新建表。
- **缺点**：
  1. 公钥被塞进 springCloud 插件的 `config` 大 JSON，**与"应用凭证"的概念模型错位**——`app_auth` 表才是 ShenYu 为应用凭证设计的一等公民。
  2. erpm-pay-center 推送侧要拼装整个 springCloud 插件 config，存在"覆盖其他 config 字段"的耦合风险（每次 PUT /plugin 是整体替换 config）。
  3. 30s 轮询有刷新窗口，公钥撤销最多延迟 30s 生效（虽 admin 推送是秒级，但 SPI 是拉取端）。
  4. 公钥无法享受 `app_auth` 表的 `enabled`（禁用即拒签）、`open`+`pathDataList`（路径级白名单）等原生能力。

> **本次决策**：`AdminConfigBizPublicKeyProvider` **整体废弃删除，不保留**。新方案用 `SignCacheBizPublicKeyProvider` 完全替代，`BizPublicKeyProvider` 接口保持单一实现。

### 1.2 目标

> **从 `app_auth` 表实时获取验签公钥**，替代从 `plugin` 表读 config JSON 的方式。

约束（用户已确认）：
1. **验签算法不变**：继续 RSA 公钥验签（`X-Pay-Sign` + `SHA256withRSA`），保留现有签名协议，业务方零改造。
2. **公钥存储列**：复用 `app_auth.app_secret`，VARCHAR 扩到 **4096**（语义在 §2.3 解释）。
3. **同步通路**：复用现有 **websocket**，SPI 只读不轮询、不自建 JDBC、不引入新中间件。
4. **交付物**：本设计文档（不写代码）。

### 1.3 非目标

- 不改动业务方签名协议（仍是 `METHOD\nURL\nTS\nNONCE\nBODY\n` + RSA + Base64）。
- 不引入 Redis / Nacos / Apollo 等新依赖。
- 不重构 `PayRsaSignService` 的签名串构造逻辑（已验证正确）。
- **不保留旧实现 `AdminConfigBizPublicKeyProvider`**：评审决策为整体替换，不留备份实现、不做双源切换。回滚依赖镜像版本管理（见 §8.3）。

---

## 2. 关键事实修正（评审必读）

调研中发现一个**颠覆性事实**，必须在评审时对齐：

### 2.1 `BaseDataCache` 不缓存 `app_auth` 数据

ShenYu 2.6.1 的 `BaseDataCache`（`shenyu-plugin-base/.../cache/BaseDataCache.java`）只持有三个 Map：

| 字段 | 类型 | 来源 |
|---|---|---|
| `PLUGIN_MAP` | `ConcurrentMap<String, PluginData>` | `CommonPluginDataSubscriber` |
| `SELECTOR_MAP` | `ConcurrentMap<String, List<SelectorData>>` | 同上 |
| `RULE_MAP` | `ConcurrentMap<String, List<RuleData>>` | 同上 |

**它没有 `AUTH_MAP`，也没有任何 `obtainAuthData(appKey)` 方法。** 全模块 `shenyu-plugin-base` 不引用 `AppAuthData`。

> 因此题目"从 app_auth 表实时获取数据"对应的网关侧缓存**不是 `BaseDataCache`，而是 `SignAuthDataCache`**。这是与当前 SPI（读 `BaseDataCache.obtainPluginData`）的根本区别。

### 2.2 `app_auth` 数据的网关侧真正归宿：`SignAuthDataCache`

| 项 | 值 |
|---|---|
| 类 | `org.apache.shenyu.plugin.sign.cache.SignAuthDataCache`（`shenyu-plugin-sign` 模块） |
| 容器 | `private static final ConcurrentMap<String, AppAuthData> AUTH_MAP`（key = **appKey**） |
| 写入者 | `SignAuthDataSubscriber.onSubscribe()` → `cacheAuthData()` |
| 读取 API | `SignAuthDataCache.getInstance().obtainAuthData(String appKey)` → `AppAuthData` |
| 订阅者注册 | `SignPluginConfiguration` 里 `@Bean AuthDataSubscriber signAuthDataSubscriber()`，`@ConditionalOnProperty(shenyu.plugins.sign.enabled, matchIfMissing=true)` |

**完整数据流（websocket 模式，目标方案）：**

```
admin: app_auth 表写入（appKey, app_secret=PEM, enabled, open, ...）
  → AppAuthServiceImpl.createOrUpdate/updateDetail/enabled/syncData
  → eventPublisher.publishEvent(DataChangedEvent(group=APP_AUTH, ...))
  → WebsocketDataChangedListener.onAppAuthChanged
  → WebsocketCollector 向所有 bootstrap 推送 APP_AUTH 帧
bootstrap: WebsocketSyncDataService 收帧
  → AuthDataHandler.doUpdate/doDelete/doRefresh
  → List<AuthDataSubscriber>.forEach(onSubscribe)   ← push 模型，秒级
  → SignAuthDataSubscriber.onSubscribe
  → SignAuthDataCache.AUTH_MAP.put(appKey, AppAuthData)
本 SPI: PayRsaSignService 验签时
  → SignAuthDataCache.getInstance().obtainAuthData(appKey).getAppSecret()
  → PemUtils.parsePem(...) → PublicKey
  → SHA256withRSA 验签
```

**关键性质：push 模型，admin 写库后秒级推送到网关缓存，SPI 无需轮询。** 这正是"实时获取"的真正含义——比当前 30s 轮询更实时。

### 2.3 为什么用 `app_secret` 列存公钥（语义说明）

`app_auth.app_secret` 在 ShenYu 原生语义中是 **HMAC 对称密钥**（`ComposableSignService` 把它当 HMAC key 算签名）。本方案**复用该列存 RSA 公钥 PEM 文本**，是一次语义重载：

| 维度 | 原生语义 | 本方案语义 |
|---|---|---|
| `app_secret` 列内容 | HMAC 对称密钥（随机串） | RSA 公钥 PEM 文本 |
| 验签算法 | HMAC-MD5/SHA256 | SHA256withRSA（非对称） |
| 网关读取者 | `ComposableSignService`（HMAC） | `PayRsaSignService`（RSA，替换前者） |

**这样做的合理性**：
1. `app_secret` 是 `app_auth` 表中**唯一一个用来承载"验签凭证"语义的列**，复用它符合"一个 appKey 一份验签材料"的心智模型。
2. 字段名 `appSecret` 在 RSA 场景下虽然字面不符（实际是 public key），但 **DTO/DO/Mapper 全链路无格式校验、无长度上限、无 `@JsonIgnore`**，PEM 文本可畅通存取与下发（见 §6 风险核实）。
3. 避免新增列带来的 admin 全链路改动（DO/Mapper/DTO/Transfer/前端）。
4. **一旦替换了 `SignService`（见 §2.4），原生 HMAC 链路不再读取 `app_secret`，语义重载不会触发冲突。**

> **命名建议**：在文档和代码注释中统一称该列为"`app_secret`（在本方案中承载 RSA 公钥 PEM）"，避免歧义。代码层用常量 + 注释显式声明，不引入新列名。

### 2.4 必须替换原生 `ComposableSignService`

原生 `ComposableSignService`（`shenyu-plugin-sign/.../service/ComposableSignService.java:133,193-205`）把 `appSecret` 当 HMAC 对称密钥，与本方案的 RSA 公钥语义**不兼容**。

替换机制已就绪：`SignPluginConfiguration.java:50` 的原生 bean 标注了：
```java
@ConditionalOnMissingBean(value = SignService.class, search = SearchStrategy.ALL)
```
只要本 SPI 暴露一个 `@Bean SignService`（即现有 `PayRsaSignService`），原生 `ComposableSignService` 就不会注册。**当前 SPI 已通过此机制替换**，本方案沿用，零改动。

替换后，生产代码中 `SignAuthDataCache.obtainAuthData()` 的唯一读取者从 `ComposableSignService` 变为 `PayRsaSignService`，无残留冲突（全仓 grep 确认）。

---

## 3. 方案对比（为什么选本方案）

| 方案 | 数据源 | 同步 | 改动量 | 实时性 | 评估 |
|---|---|---|---|---|---|
| **A. 现状** | `plugin.config` JSON | websocket PLUGIN | 已上线 | 30s 轮询 | 公钥与插件 config 耦合，撤销延迟 |
| **B. 本方案** | `app_auth.app_secret`(扩列) | websocket APP_AUTH | 小（见 §6） | **秒级 push** | ✅ 推荐 |
| C. 新增 `app_public_key` 列 | 新列 | websocket APP_AUTH | 大（admin 全链路） | 秒级 push | 语义更清晰，但改动面大，本期不做 |
| D. 网关侧直连 MySQL | `app_auth` 表 | 自建 JDBC 轮询 | 中 | 轮询 | ❌ 违背 ShenYu 架构，引入 DB 连接池，不推荐 |
| E. 切 HMAC（用 appSecret 原生） | `app_auth.app_secret` | websocket APP_AUTH | 中（业务方改签算法） | 秒级 push | ❌ 破坏性变更，所有调用方重新对接 |
| F. ext_info 列存 JSON 公钥 | `app_auth.ext_info` | 需自定义 Subscriber 解析 | 大 | 秒级 push | `AppAuthData` DTO 不下发 ext_info，需改 admin DTO 或自定义 Subscriber，复杂 |

**结论**：方案 B 在"保留 RSA 协议 + 复用 websocket + 改动可控 + 实时性最佳"四个维度上最优。

---

## 4. 总体设计

### 4.1 架构图

```
┌─────────────────────────────┐         ┌──────────────────────────────────────┐
│  erpm-pay-center / 运维      │         │  ShenYu Admin (2.6.1)                 │
│  POST /appAuth/updateDetail  │ ──HTTP──│  AppAuthController                    │
│  body: AppAuthDTO{           │         │   → AppAuthServiceImpl.createOrUpdate │
│    appKey,                   │         │   → AppAuthMapper (app_auth 表)       │
│    appSecret: <PEM 公钥>,    │         │   → publish DataChangedEvent(APP_AUTH)│
│    enabled: true, open:false │         │   → WebsocketDataChangedListener      │
│  }                           │         └──────────────┬───────────────────────┘
└─────────────────────────────┘                        │ websocket (APP_AUTH group)
                                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  ShenYu Bootstrap (镜像 shenyu-bootstrap:2.6.1-sign-latest, ext-lib/ 本 jar)   │
│                                                                                │
│  WebsocketSyncDataService                                                     │
│    → AuthDataHandler.doUpdate ──forEach──► SignAuthDataSubscriber.onSubscribe │
│                                              (原生, 不可改)                    │
│                                                │                              │
│                                                ▼                              │
│                                SignAuthDataCache.AUTH_MAP (key=appKey)         │
│                                                ▲                              │
│                                                │ 读 (O(1), 零 I/O 零锁)        │
│                                                │                              │
│  SignPlugin ──► PayRsaSignService.signatureVerify(exchange)                   │
│    (注入本 SPI 的 SignService Bean)     │                                     │
│                                         ▼                                      │
│                SignCacheBizPublicKeyProvider.currentKey(appKey)               │
│                  (本 SPI 唯一实现，替代旧 AdminConfigBizPublicKeyProvider)       │
│                  → 先查本地二级缓存 appKey→PublicKey（命中直接返回，热路径）     │
│                  → miss 时 SignAuthDataCache.obtainAuthData(appKey).getAppSecret() │
│                  → PemUtils.parsePem(PEM) → PublicKey                          │
│                  → 回填二级缓存                                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 设计要点

1. **零轮询**：不引入任何 `ScheduledExecutorService`。数据由 websocket push 进 `SignAuthDataCache`，天然实时。旧实现的轮询线程随类一并删除。
2. **本地二级缓存（parsePem 结果）**：`SignAuthDataCache` 存的是 PEM 字符串，每次验签都 `parsePem` 有开销（Base64 解码 + KeySpec + KeyFactory）。设计一个 `appKey → PublicKey` 的本地缓存，由自定义 `AuthDataSubscriber` 在 admin 推送时主动维护（见 §7.3a），key 失效或变化时重 parse。
3. **优雅降级沿用**：保留"缓存非空时 miss 即 401，缓存空时 not initialized"的就绪语义（沿用旧实现的错误信息分级思路，但实现移植到新类）。
4. **`enabled` 字段利用**：`app_auth.enabled=false` 时拒签（原生能力，免费获得）。
5. **单一实现**：`BizPublicKeyProvider` 只保留 `SignCacheBizPublicKeyProvider` 一个实现，无配置开关、无双源切换。

---

## 5. 类设计

### 5.1 新增类：`SignCacheBizPublicKeyProvider`

**职责**：实现 `BizPublicKeyProvider`，从 `SignAuthDataCache` 读 `app_auth` 数据，解析 PEM，缓存 `PublicKey`。

**位置**：`org.apache.shenyu.plugin.sign.custom.SignCacheBizPublicKeyProvider`

**关键设计**：

```java
public final class SignCacheBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    // 二级缓存：appKey → PublicKey（parsePem 结果）
    // volatile + unmodifiableMap，沿用现有 safe publication 模型
    private volatile Map<String, PublicKey> cacheMap = Collections.emptyMap();

    // appKey → 上次 parse 用的 PEM 指纹（SHA-256），用于判断 PEM 是否变化、是否需要重 parse
    // 用指纹而非原文，避免大字符串比较开销，且对换行差异免疫
    private volatile Map<String, String> pemFingerprintMap = Collections.emptyMap();

    /**
     * 热路径：Netty EventLoop 线程调用，零 I/O 零锁。
     *
     * 流程：
     *   1. 先查二级缓存 cacheMap，命中直接返回（绝大多数请求走这里）
     *   2. miss 时从 SignAuthDataCache 读 AppAuthData
     *      a. AppAuthData == null → 缓存空判定就绪状态，抛 not initialized / not found
     *      b. enabled == false → 抛 appKey disabled
     *      c. PEM 指纹与上次相同 → 说明 cacheMap miss 是因为新进程/重启，补 parse
     *      d. PEM 变化 → parse 新 PEM，更新二级缓存
     *   3. parse 在调用线程同步执行（首次/变更时），后续命中二级缓存
     *
     * 注：parse 是 CPU 密集（Base64 + KeyFactory），但仅在 miss/变更时发生，
     *     热路径（命中二级缓存）仍是 O(1) HashMap.get。
     */
    @Override
    public PublicKey currentKey(String appKey) throws Exception {
        // ... 见伪代码下方详细说明
    }

    @Override
    public void close() {
        // 无 scheduler 可关，保留接口对称性（AutoCloseable）
    }
}
```

**热路径详细逻辑（伪代码）**：

```java
PublicKey currentKey(String appKey) throws Exception {
    if (appKey == null || appKey.trim().isEmpty()) {
        throw new IllegalArgumentException("appKey must not be empty");
    }
    String normalized = appKey.trim();

    // 1. 二级缓存命中（热路径，99%+ 走这里）
    PublicKey cached = cacheMap.get(normalized);
    if (cached != null) {
        return cached;
    }

    // 2. miss → 从 SignAuthDataCache 回源
    AppAuthData auth = SignAuthDataCache.getInstance().obtainAuthData(normalized);
    if (auth == null) {
        // 就绪语义：SignAuthDataCache 全空 = 尚未完成首次 websocket 同步
        //         非空但本 appKey 不在 = 业务方未配置
        //   用 AUTH_MAP 是否空区分（需 SignAuthDataCache 暴露 size/isEmpty，见 §5.3）
        if (SignAuthDataCache.getInstance().isEmpty()) {
            throw new IllegalStateException(
                "public key provider not initialized yet (SignAuthDataCache empty), appKey=" + appKey);
        }
        throw new IllegalStateException(
            "no app_auth record for appKey=" + appKey + " (synced but not found)");
    }
    if (Boolean.FALSE.equals(auth.getEnabled())) {
        throw new IllegalStateException("appKey=" + appKey + " is disabled in app_auth");
    }

    String pem = auth.getAppSecret();
    if (pem == null || pem.trim().isEmpty()) {
        throw new IllegalStateException("app_secret (PEM) is empty for appKey=" + appKey);
    }

    // 3. PEM 指纹比对，决定是否复用已 parse 的 PublicKey
    String fp = PemUtils.fingerprintOf(pem.trim());
    String prevFp = pemFingerprintMap.get(normalized);

    // 3a. 并发安全更新二级缓存（copy-on-write 整体发布）
    //     注意：多线程并发 miss 同一 appKey 时，可能重复 parse，但结果幂等，可接受
    PublicKey parsed = PemUtils.parsePem(pem.trim());
    Map<String, PublicKey> nextMap = new HashMap<>(cacheMap);
    nextMap.put(normalized, parsed);
    Map<String, String> nextFp = new HashMap<>(pemFingerprintMap);
    nextFp.put(normalized, fp);
    cacheMap = Collections.unmodifiableMap(nextMap);
    pemFingerprintMap = Collections.unmodifiableMap(nextFp);
    LOG.info("[GW-Sign] app_auth 公钥加载/更新 appKey={} fp={}", normalized, fp);
    return parsed;
}
```

> **并发说明**：上述 miss 路径在多线程并发时有重复 parse 的可能。考虑到：(a) parse 单次耗时可忽略（<1ms）；(b) 仅在首次/变更时发生；(c) 加锁会让热路径复杂化。**接受偶发重复 parse，换取热路径零锁。** 与现有 `AdminConfigBizPublicKeyProvider` 的设计哲学一致。

### 5.2 修改类：`PayRsaSignConfiguration`

**职责**：装配唯一的 `BizPublicKeyProvider` 实现和 `SignService`。

**装配逻辑（伪代码）**：

```java
@Configuration
public class PayRsaSignConfiguration {

    @Bean(destroyMethod = "close")
    public SignCacheBizPublicKeyProvider bizPublicKeyProvider() {
        LOG.info("[GW-Sign] SignCacheBizPublicKeyProvider 已注册（公钥源 = app_auth, "
                + "读 SignAuthDataCache, websocket push, 零轮询）");
        return new SignCacheBizPublicKeyProvider();
    }

    @Bean
    public AuthDataSubscriber bizPublicKeyAuthDataSubscriber(
            final SignCacheBizPublicKeyProvider provider) {
        // 自定义订阅器（§7.3a），与原生 SignAuthDataSubscriber 并存，
        // 负责主动维护二级缓存，使公钥轮换/撤销秒级生效
        LOG.info("[GW-Sign] BizPublicKeyAuthDataSubscriber 已注册（预热/失效二级缓存）");
        return new BizPublicKeyAuthDataSubscriber(provider);
    }

    @Bean
    public SignService signService(final BizPublicKeyProvider provider) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册（替换原生 ComposableSignService）");
        return new PayRsaSignService(provider);
    }
}
```

**与旧版本的差异**：
- 删除 `readRefreshIntervalSeconds` 工具方法和 `gw.springcloud.refresh-interval-seconds` 配置项（零轮询，不再需要）。
- 删除 `Environment` 注入（不再读配置）。
- 不再注册 `AdminConfigBizPublicKeyProvider`。
- 新增注册 `BizPublicKeyAuthDataSubscriber`（§7.3a）。

> `BizPublicKeyProvider` 接口、`PayRsaSignService`、`PemUtils` **均无需改动**——本方案只换数据源实现，验签逻辑完全复用。这是接口抽象带来的红利。

### 5.3 `SignAuthDataCache` 的 `isEmpty` 可见性

**潜在问题**：`SignAuthDataCache` 当前公开方法只有 `getInstance / cacheAuthData / removeAuthData / obtainAuthData`，**没有 `isEmpty()` 或 `size()`**。本方案的就绪语义（§5.1 步骤 2）依赖"区分全空 vs 本 appKey 不在"。

**两个解决方案**：

| 方案 | 做法 | 评估 |
|---|---|---|
| **5.3a（推荐）** | 不依赖 `isEmpty`，统一抛 `"no app_auth record for appKey=..."`。首启竞态（websocket 未同步完）会被这条消息覆盖，运维通过日志区分（首次同步前所有请求都报此错，同步后消失）。 | 零改动 `SignAuthDataCache`，但首启窗口的错误信息不够精准 |
| 5.3b | 用反射读 `SignAuthDataCache.AUTH_MAP`（私有静态字段）判空 | 反射脆弱，升级风险，不推荐 |
| 5.3c | 自己实现一个 `AuthDataSubscriber`，维护本地 `appKeySet`，用本地 set 判空 | 多一份订阅器，复杂度上升 |

> **建议选 5.3a**，牺牲一点首启错误信息的精准度，换取零改动原生类。首启竞态在实际中很短（websocket 连接后秒级全量同步），且 admin 侧有 `syncData` 全量推送兜底。

### 5.4 类图

```
<<interface>>
BizPublicKeyProvider
+ currentKey(appKey): PublicKey
        ▲
        │ implements（唯一实现）
        │
SignCacheBizPublicKeyProvider   ← 新增（替代旧 AdminConfigBizPublicKeyProvider）
 (app_auth 源, 读 SignAuthDataCache, 本地二级缓存)
        │
        │ @Bean（无条件，单一实现）
        │
        │ 注入
        ▼
PayRsaSignService (implements SignService)
        │ 替换原生 ComposableSignService（@ConditionalOnMissingBean）
        ▼
SignPlugin

<<interface>>
AuthDataSubscriber
        ▲
        │ implements（新增第二个实现，与原生 SignAuthDataSubscriber 并存）
        │
BizPublicKeyAuthDataSubscriber  ← 新增（§7.3a）
 (onSubscribe → 预热二级缓存；unSubscribe → evict；refresh → evictAll)
        │ 持有
        ▼
SignCacheBizPublicKeyProvider
```

---

## 6. 改动点清单

### 6.1 数据库 DDL（必须，前置）

**当前**：`app_auth.app_secret VARCHAR(128) NOT NULL`（`db/init/mysql/schema.sql:95`，pg/og/oracle 同为 128）。

**RSA PEM 长度评估**：
- RSA-2048 公钥 PEM ≈ 450 字符
- RSA-4096 公钥 PEM ≈ 800 字符
- 4096 列长对两者都绰绰有余（即使 RSA-8192 ≈ 1600 字符也能装）

**MySQL 变更脚本**（需 DBA 执行）：

```sql
-- 文件名建议：db/upgrade/2.6.1-app-auth-app-secret-to-4096-mysql.sql
ALTER TABLE `app_auth`
  MODIFY COLUMN `app_secret` VARCHAR(4096)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
  NOT NULL COMMENT '验签凭证（本方案中承载 RSA 公钥 PEM；原生语义为 HMAC 对称密钥）';
```

**配套改动（保持各 DB schema 一致）**：
- `db/init/mysql/schema.sql:95`
- `db/init/pg/create-table.sql:120`
- `db/init/og/create-table.sql:120`
- `db/init/oracle/schema.sql:479`

> **⚠️ 重要提醒**：`db/init/mysql/schema.sql:2060` 和 `2.6.0-upgrade-2.6.1-mysql.sql:73` 里的 `app_secret varchar(255)` 是 **`alert_receiver` 表**（告警接收器，企业微信/飞书机器人），与 `app_auth` **无关，切勿误改**。

**回滚 DDL**（必要时）：
```sql
ALTER TABLE `app_auth` MODIFY COLUMN `app_secret` VARCHAR(128)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
-- 注意：回滚前需确保所有 app_secret 值 ≤ 128 字符，否则截断
```

### 6.2 admin 侧（可选，仅当需要从 admin 推送公钥时）

**现状**：admin 全链路（DO/Mapper/DTO/Transfer）对 `appSecret` **无格式校验、无长度上限**（仅 `@NotBlank`），PEM 文本可畅通存取。**无需改动 admin 代码。**

**写入公钥的正确姿势（运维 / erpm-pay-center 遵循）**：

| 接口 | 方法 | 是否推荐 | 原因 |
|---|---|---|---|
| `/appAuth/apply` | POST AuthApplyDTO | ❌ 不可用 | `AuthApplyDTO` 无 appSecret 字段，`AppAuthDO.create(AuthApplyDTO)` 会用 `SignUtils.generateKey()` 强制覆盖为随机 UUID |
| `/appAuth/createOrUpdate` | POST AppAuthDTO | ⚠️ 慎用 | `createOrUpdate` 在 id 为空时也会 `setAppSecret(SignUtils.generateKey())` 覆盖；仅更新已有记录时可用 |
| **`/appAuth/updateDetail`** | POST AppAuthDTO | ✅ **推荐** | `@RequestBody @Valid`，appSecret 仅 `@NotBlank`，JSON body 可放任意长 PEM 文本（含换行/`=`/`-----`） |
| `/appAuth/updateSl` | GET ?appSecret= | ❌ 不推荐 | PEM 的 `\n`/`+`/`=`/空格在 query string 需 URL 编码，易出错；受 URL 长度限制 |

**推荐工作流**：
1. 先 `POST /appAuth/apply` 申请，拿到 appKey（此时 appSecret 是随机 UUID）。
2. 再 `POST /appAuth/updateDetail`，body 中 `appSecret` 字段填 PEM 公钥，覆盖随机值。
3. admin 自动通过 websocket 推送到网关 `SignAuthDataCache`。

### 6.3 SPI 工程（`shenyu-sign-gateway-spi`）

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `SignCacheBizPublicKeyProvider.java` | **新增** | §5.1，读 `SignAuthDataCache`，二级缓存 PublicKey |
| `BizPublicKeyAuthDataSubscriber.java` | **新增** | §7.3a，主动维护二级缓存（预热/失效） |
| `PayRsaSignConfiguration.java` | **修改** | §5.2，装配新 Provider + 新 Subscriber，删除旧 Bean 和配置项读取 |
| `AdminConfigBizPublicKeyProvider.java` | **删除** | 旧实现整体废弃，不保留 |
| `BizPublicKeyProvider.java` | 不变 | 接口契约不变 |
| `PayRsaSignService.java` | 不变 | 验签逻辑完全复用 |
| `PemUtils.java` | 不变（已有 `parsePem` + `fingerprintOf`） | 复用现有实现 |
| `pom.xml` | **可选微调** | `shenyu-plugin-sign` 已 provided（含 `SignAuthDataCache`），无需新增依赖；可移除残留的 `httpclient`（未被使用） |
| `META-INF/spring.factories` | 不变 | 仍只注册 `PayRsaSignConfiguration` |

**pom.xml 依赖确认**：
- `SignAuthDataCache` 位于 `shenyu-plugin-sign` 模块 → **已在 pom 中声明**（`provided`），✅ 无需新增。
- `AppAuthData` 位于 `shenyu-common` 模块 → `shenyu-plugin-sign` 传递依赖 `shenyu-common`，✅ 可用。
- `AuthDataSubscriber` 接口位于 `shenyu-sync-data-api` 模块 → `shenyu-plugin-sign` **传递依赖** `shenyu-sync-data-api:2.6.1:compile`（已通过 `mvn dependency:tree` 确认），✅ 无需在 pom 新增声明。

### 6.4 bootstrap 侧（部署约束，无代码改动）

**必须保持**：`shenyu.plugins.sign.enabled=true`（默认 true，`SignPluginConfiguration:41` `matchIfMissing=true`）。
- 原因：`SignAuthDataSubscriber` 由该配置控制注册；一旦禁用，`SignAuthDataCache` 永远为空，方案失效。
- 固化方式：docker-compose 不显式设此项（走默认），或在文档中明确标注"禁止设为 false"。

**必须配置**：`shenyu.sync.websocket.urls=ws://shenyu-admin:9095/websocket`（现有，不变）。

---

## 7. 端到端数据流（时序）

### 7.1 正常验签请求

```
Client → Bootstrap
  Headers: X-Pay-App-Key=YYT, X-Pay-Timestamp, X-Pay-Nonce, X-Pay-Sign

SignPlugin(order=50).doExecute
  → PayRsaSignService.signatureVerify(exchange)
    → 读 X-Pay-App-Key = "YYT"
    → bizPublicKeyProvider.currentKey("YYT")
      → [SignCacheBizPublicKeyProvider]
      → cacheMap.get("YYT") 命中? → 返回 PublicKey（热路径）
      → miss → SignAuthDataCache.obtainAuthData("YYT")
        → AppAuthData{appKey=YYT, appSecret=<PEM>, enabled=true}
        → enabled 检查 ✓
        → PemUtils.parsePem(PEM) → PublicKey
        → 更新 cacheMap，返回
    → 用 PublicKey 做 SHA256withRSA 验签
      → 通过 → 放行
      → 失败 → fail401 → HTTP 401
```

### 7.2 公钥更新（撤销/轮换）

```
运维/erpm-pay-center POST /appAuth/updateDetail
  body: {appKey: YYT, appSecret: <新PEM>, enabled: true}

admin: AppAuthServiceImpl.updateDetail
  → UPDATE app_auth SET app_secret=<新PEM> WHERE app_key='YYT'
  → publishEvent(DataChangedEvent(APP_AUTH, UPDATE, [AppAuthData{appSecret=新PEM}]))

admin: WebsocketDataChangedListener.onAppAuthChanged
  → 向所有 bootstrap 推送 APP_AUTH 帧

bootstrap: AuthDataHandler.doUpdate
  → SignAuthDataSubscriber.onSubscribe(AppAuthData{appSecret=新PEM})
  → SignAuthDataCache.AUTH_MAP.put("YYT", 新AppAuthData)   ← 秒级生效

下一次请求 YYT:
  → SignCacheBizPublicKeyProvider.currentKey("YYT")
  → cacheMap 命中旧 PublicKey（仍是旧 PEM parse 的）
  → ⚠️ 注意：二级缓存不会自动失效！

  → 方案：currentKey 内增加"PEM 指纹比对"
    每次命中二级缓存时，可选地比对 SignAuthDataCache 中当前 PEM 指纹
    与 pemFingerprintMap 中记录的指纹；不一致则重 parse
  → 但这会让热路径多一次 Map.get（SignAuthDataCache）+ 字符串比较
  → 折中：不做主动比对，依赖"二级缓存 miss 才重 parse"
    即：撤销/轮换后，需要等二级缓存被清空才生效

  → 最简清空策略：在 SignCacheBizPublicKeyProvider 内监听变更
    （见 §7.3）
```

### 7.3 二级缓存失效策略（关键设计决策）

**问题**：`SignCacheBizPublicKeyProvider.cacheMap` 是本地 `appKey → PublicKey`，PEM 变化后不会自动失效。

**三种失效策略**：

| 策略 | 做法 | 实时性 | 复杂度 | 评估 |
|---|---|---|---|---|
| **7.3a（推荐 v1）** | 实现自定义 `AuthDataSubscriber`，在 `onSubscribe/unSubscribe` 时主动更新/移除二级缓存 | 秒级 | 低 | ✅ 与 push 模型契合，撤销实时生效 |
| 7.3b | 热路径每次命中二级缓存时，额外查 `SignAuthDataCache` 比对 PEM 指纹 | 秒级 | 中 | 热路径多一次 Map.get，违背"零 I/O 零锁"哲学 |
| 7.3c | 不主动失效，依赖缓存 miss（如重启）才更新 | 重启级 | 极低 | ❌ 撤销不实时，安全风险 |

**推荐 7.3a**：新增一个轻量 `AuthDataSubscriber`，与原生 `SignAuthDataSubscriber` 并存（Spring 自动收集 `List<AuthDataSubscriber>`），负责维护 `SignCacheBizPublicKeyProvider` 的二级缓存一致性。

**新增类**：`BizPublicKeyAuthDataSubscriber`

```java
@Component  // 或通过 @Bean 在 Configuration 注册
public class BizPublicKeyAuthDataSubscriber implements AuthDataSubscriber {

    private final SignCacheBizPublicKeyProvider provider;

    // 构造注入 provider（双向引用，注意循环依赖——用 ObjectProvider 或 @Lazy 解决）

    @Override
    public void onSubscribe(AppAuthData data) {
        // AppAuthData 到达 → 预 parse 并预热二级缓存
        // 失败（PEM 非法）不抛，仅告警，保留旧缓存
        provider.refreshFromAppAuthData(data);
    }

    @Override
    public void unSubscribe(AppAuthData data) {
        // appKey 删除 → 从二级缓存移除
        provider.evict(data.getAppKey());
    }

    @Override
    public void refresh() {
        // 全量刷新 → 清空二级缓存，下次请求懒重建
        provider.evictAll();
    }
}
```

**这样 `SignCacheBizPublicKeyProvider.currentKey` 可以简化**：二级缓存由 subscriber 主动维护，热路径纯读，无需 miss 回源逻辑（回源作为兜底即可）。

> **注**：本设计文档默认采用 7.3a。若评审认为复杂度过高，可先上 7.3c（重启生效）作为 MVP，后续迭代到 7.3a。

---

## 8. 部署、灰度与回滚

### 8.1 部署步骤

1. **DBA 执行 DDL**（§6.1）：`ALTER TABLE app_auth MODIFY COLUMN app_secret VARCHAR(4096) ...`
2. **数据迁移**（如从旧 plugin_config 方案迁移）：
   - 对每个 appKey，从 `plugin.config` 的 `gw.springcloud.app-key.<appKey>` 读出 PEM
   - 通过 `POST /appAuth/updateDetail` 写入 `app_auth.app_secret`
   - 校验 `app_auth` 表对应记录 `enabled=true`
3. **构建 SPI jar**：`mvn clean package` → `target/shenyu-sign-gateway-spi-2.6.1.jar`
4. **更新镜像**：`Dockerfile` COPY jar 到 `/opt/shenyu-bootstrap/ext-lib/`，tag `shenyu-bootstrap:2.6.1-sign-appauth-latest`
5. **docker-compose 配置**（无新增配置项，保持简洁）：
   ```yaml
   shenyu-bootstrap-261:
     image: shenyu-bootstrap:2.6.1-sign-appauth-latest
     environment:
       - shenyu.sync.websocket.urls=ws://shenyu-admin-261:9095/websocket
       # 确保未设 shenyu.plugins.sign.enabled=false（默认 true，必须保持）
       # 旧配置 GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS 已废弃，新方案零轮询不需要
   ```
6. **滚动重启 bootstrap**。

### 8.2 灰度验证

1. 先在测试环境跑通 §11 SOP 全部用例。
2. 生产灰度：选 1 个低流量 appKey，先在 `app_auth` 写入 PEM，观察该 appKey 请求验签成功率。
3. 全量：逐步把所有 appKey 的 PEM 从 `plugin.config` 迁到 `app_auth`。
4. 迁移完成后，可清理 `plugin.config` 中的 `gw.springcloud.app-key.*` 字段（可选）。

### 8.3 回滚

由于旧实现 `AdminConfigBizPublicKeyProvider` 已删除，回滚**不再支持运行时切换数据源**，改用以下两级回滚：

**L1（推荐，应用层回滚）——回退镜像版本**：
1. docker-compose 切回旧镜像 tag（如 `shenyu-bootstrap:2.6.1-sign-latest`，内含旧版 SPI jar，读 `plugin.config`）。
2. 前提：迁移阶段在 `plugin.config` 中**保留** `gw.springcloud.app-key.*` 双份数据，直至新方案稳定（建议保留 1-2 个观察周期）。
3. 回滚后 `app_auth.app_secret` 扩列（VARCHAR 4096）无需回退，兼容旧数据。

**L2（兜底，DDL 回滚）——仅当需要彻底复原时**：
```sql
ALTER TABLE `app_auth` MODIFY COLUMN `app_secret` VARCHAR(128)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
-- 注意：回滚前必须确保所有 app_secret 值 ≤ 128 字符（即已清空所有 PEM 公钥），否则 MySQL 会截断
```

**灰度建议**：生产灰度期间，旧镜像 tag 和新镜像 tag 并存于镜像仓库，`plugin.config` 与 `app_auth` 双份数据共存，确保 L1 回滚可在分钟级完成。

---

## 9. 风险与对策

| # | 风险 | 严重度 | 对策 |
|---|---|---|---|
| R1 | `app_secret` 列名语义重载（实际存公钥）造成维护困惑 | 中 | 文档 + 代码注释 + 常量命名显式声明（如 `APP_SECRET_PUBKEY_SEMANTICS`）；迁移完成后在 `app_auth` 表加列注释 |
| R2 | 误用 `/appAuth/apply` 或 `/createOrUpdate` 创建记录，导致 PEM 被 `SignUtils.generateKey()` 覆盖 | 中 | 工作流约束（§6.2）：必须先 apply 再 updateDetail；erpm-pay-center 推送逻辑固化此顺序 |
| R3 | `shenyu.plugins.sign.enabled=false` 导致 `SignAuthDataCache` 为空 | 高 | 部署约束固化（§6.4）；可在 SPI 启动时检测并告警（可选增强） |
| R4 | 二级缓存（PublicKey）与 `SignAuthDataCache`（PEM）不一致 | 中 | 采用策略 7.3a（自定义 AuthDataSubscriber 主动维护） |
| R5 | 首启竞态：websocket 未完成全量同步前，所有请求 401 | 低 | websocket 连接后秒级同步；启动日志观察 `[GW-Sign]` 同步完成；首启窗口极短 |
| R6 | PEM 文本含 `\n`/`=`/`-----`，通过 GET 接口写入时 URL 编码出错 | 低 | 约束用 POST `/appAuth/updateDetail`（JSON body）写入 |
| R7 | `app_auth` 表被 admin 前端"密钥重置"功能覆盖 PEM | 中 | 运维约束：禁止在 admin 前端对该表记录点"重置密钥"；或加 admin 侧拦截（本期不做） |
| R8 | 多个 `AuthDataSubscriber`（原生 + 本方案新增）并存，行为叠加 | 低 | 原生 `SignAuthDataSubscriber` 写 `SignAuthDataCache`（我们读它）；本方案 subscriber 写二级缓存；职责正交，无冲突 |
| R9 | `SignAuthDataCache` 无 `isEmpty()` 公开方法，就绪判断不精准 | 低 | 采用策略 5.3a（统一错误信息，不依赖 isEmpty） |

---

## 10. 测试矩阵

### 10.1 单元测试（`SignCacheBizPublicKeyProvider`）

| 用例 | 输入 | 期望 |
|---|---|---|
| UT1 | 二级缓存命中 | 直接返回，不查 SignAuthDataCache |
| UT2 | miss + SignAuthDataCache 有 appKey + enabled + 合法 PEM | parse 成功，缓存 PublicKey，返回 |
| UT3 | miss + SignAuthDataCache 有 appKey + enabled=false | 抛 "disabled" |
| UT4 | miss + SignAuthDataCache 有 appKey + appSecret 为空 | 抛 "PEM is empty" |
| UT5 | miss + SignAuthDataCache 有 appKey + PEM 非法 | 抛 parse 异常，不污染缓存 |
| UT6 | miss + SignAuthDataCache 无此 appKey | 抛 "not found" |
| UT7 | appKey 为空/null | 抛 IllegalArgumentException |
| UT8 | subscriber.onSubscribe 触发预热 | 二级缓存更新 |
| UT9 | subscriber.unSubscribe 触发移除 | 二级缓存 evict |
| UT10 | subscriber.refresh 触发全清 | 二级缓存清空 |
| UT11 | 并发 miss 同一 appKey | 不抛错，最终缓存一致（允许重复 parse） |

### 10.2 集成测试（沿用 §11 SOP）

复用现有 `BaseDataCache验签测试SOP.md` 的 7 个用例，数据源从 `plugin.config` 改为 `app_auth` 表：

| 用例 | 操作 | 期望 |
|---|---|---|
| Case1 | 无签名头 | 401 |
| Case2 | 错误签名 | 401 |
| Case3 | 合法 YYT（PEM 在 app_auth） | 200 |
| Case4 | 合法 SYD（第二个 appKey，验缓存） | 200 |
| Case5 | 未知 appKey | 401 |
| Case6 | 合法 POST save | 200 |
| Case7 **关键** | 动态更新：改 app_auth.app_secret → admin 推送 → 验证秒级生效（200 转 401） | 200 → 401，**延迟 < 5s**（对比旧方案 30s） |
| Case8 新增 | app_auth.enabled=false → 验证拒签 | 401 |
| Case9 新增 | 公钥轮换：updateDetail 写新 PEM → 新签名通过、旧签名失败 | 旧签名 401，新签名 200 |

### 10.3 性能验证

- **热路径基准**：`currentKey` 命中二级缓存时，单次耗时应 < 100ns（HashMap.get + volatile 读）。
- **parse 开销**：单次 `PemUtils.parsePem` 应 < 1ms（Base64 + KeyFactory）。
- **对比**：与旧方案（30s 轮询）热路径性能持平（都是 HashMap.get），但撤销实时性从 30s 提升到秒级。

---

## 11. SOP（测试标准操作流程，基于现有 SOP 改造）

### 11.1 环境拓扑（不变）

```
MySQL mysql57 (库 shenyu_261, erpm_pay_center)
  └─ app_auth 表（app_secret 已扩到 VARCHAR(4096)）
Admin shenyu-admin-261 (9096)
Bootstrap shenyu-bootstrap-261 (9196, 镜像 2.6.1-sign-appauth-latest)
  └─ ext-lib/shenyu-sign-gateway-spi-2.6.1.jar（新版本）
Nacos (8848)
测试后端 shenyu-springcloud-demo (8470)
```

### 11.2 测试密钥（不变）

`shenyu-springcloud-demo/src/main/resources/keys/` 下 `biz-public-key.pem`（key1, YYT）和 `biz-public-key-2.pem`（key2, SYD）。

### 11.3 数据准备 SQL（替换旧方案的 plugin.config 注入）

```sql
-- 确保 app_secret 列已扩到 4096
-- 为 YYT 写入公钥（PEM 需转义换行，或用 SQL 的 REPLACE）
-- 建议通过 admin REST API updateDetail 写入，避免 SQL 转义问题
-- 这里给出 SQL 直写方式（MySQL，用 HEX 避免转义）：

-- 方式1：admin REST（推荐）
-- POST http://admin:9096/appAuth/updateDetail
-- Body: {"appKey":"YYT","appSecret":"<PEM全文>","enabled":true,"open":false}

-- 方式2：直改 DB（需重启 admin 触发 websocket 重推，或调 /appAuth/syncData）
UPDATE app_auth SET app_secret = '<PEM全文，换行用 \n>', enabled = 1
  WHERE app_key = 'YYT';
-- 然后 POST http://admin:9096/appAuth/syncData 触发全量同步
```

### 11.4 验证命令（沿用现有 `sign-request.sh`）

签名串格式不变（`METHOD\nURL\nTS\nNONCE\nBODY\n` + SHA256withRSA + Base64），脚本无需改造。

---

## 12. 实现优先级与里程碑（建议）

| 阶段 | 内容 | 工作量估算 |
|---|---|---|
| M1 | DDL 变更 + 数据迁移脚本 | 0.5d |
| M2 | `SignCacheBizPublicKeyProvider`（含二级缓存 + miss 回源） + 单测 | 0.5d |
| M3 | `BizPublicKeyAuthDataSubscriber`（策略 7.3a，秒级失效） + 单测 | 0.5d |
| M4 | `PayRsaSignConfiguration` 改造（装配新 Bean，删除旧实现与配置项） | 0.5d |
| M5 | 集成测试 SOP 跑通 | 0.5d |
| M6 | 生产灰度 + 全量迁移 | 1d |

---

## 13. 附录

### 13.1 关键源码引用（ShenYu 2.6.1）

| 类 | 路径 |
|---|---|
| `BaseDataCache` | `shenyu-plugin/shenyu-plugin-base/src/main/java/org/apache/shenyu/plugin/base/cache/BaseDataCache.java` |
| `SignAuthDataCache` | `shenyu-plugin/shenyu-plugin-security/shenyu-plugin-sign/src/main/java/org/apache/shenyu/plugin/sign/cache/SignAuthDataCache.java` |
| `SignAuthDataSubscriber` | `.../shenyu-plugin-sign/src/main/java/org/apache/shenyu/plugin/sign/subscriber/SignAuthDataSubscriber.java` |
| `AuthDataSubscriber`（SPI 接口） | `shenyu-sync-data-center/shenyu-sync-data-api/src/main/java/org/apache/shenyu/sync/data/api/AuthDataSubscriber.java` |
| `ComposableSignService`（被替换） | `.../shenyu-plugin-sign/src/main/java/org/apache/shenyu/plugin/sign/service/ComposableSignService.java` |
| `AppAuthData`（DTO） | `shenyu-common/src/main/java/org/apache/shenyu/common/dto/AppAuthData.java` |
| `AppAuthDO` | `shenyu-admin/src/main/java/org/apache/shenyu/admin/model/entity/AppAuthDO.java` |
| `AppAuthServiceImpl` | `shenyu-admin/src/main/java/org/apache/shenyu/admin/service/impl/AppAuthServiceImpl.java` |
| `AppAuthController` | `shenyu-admin/src/main/java/org/apache/shenyu/admin/controller/AppAuthController.java` |
| `app-auth-sqlmap.xml` | `shenyu-admin/src/main/resources/mappers/app-auth-sqlmap.xml` |
| `schema.sql`（app_auth DDL） | `db/init/mysql/schema.sql:89-104` |
| `SignPluginConfiguration`（订阅者注册） | `shenyu-spring-boot-starter/.../shenyu-spring-boot-starter-plugin-sign/.../SignPluginConfiguration.java` |

### 13.2 本 SPI 工程文件（改造对象）

| 文件 | 改动 |
|---|---|
| `src/main/java/.../custom/AdminConfigBizPublicKeyProvider.java` | **删除**（旧实现整体废弃） |
| `src/main/java/.../custom/BizPublicKeyProvider.java` | 不变 |
| `src/main/java/.../custom/PayRsaSignConfiguration.java` | 改：装配新 Provider + 新 Subscriber，删除旧 Bean 与配置项读取 |
| `src/main/java/.../custom/PayRsaSignService.java` | 不变 |
| `src/main/java/.../custom/PemUtils.java` | 不变（已有 `parsePem` + `fingerprintOf`） |
| `src/main/java/.../custom/SignCacheBizPublicKeyProvider.java` | **新增** |
| `src/main/java/.../custom/BizPublicKeyAuthDataSubscriber.java` | **新增**（策略 7.3a） |
| `pom.xml` | 可选：移除残留 httpclient；无需新增依赖（`AuthDataSubscriber` 经 `shenyu-plugin-sign` 传递可用） |
| `src/main/resources/META-INF/spring.factories` | 不变 |

### 13.3 术语表

| 术语 | 含义 |
|---|---|
| app_auth 表 | ShenYu 应用认证表，存储 appKey/appSecret/enabled 等 |
| SignAuthDataCache | 网关侧 app_auth 数据的内存缓存（key=appKey） |
| BaseDataCache | 网关侧 plugin/selector/rule 的内存缓存（不含 app_auth） |
| PLUGIN group | websocket 同步分组之一，承载插件配置 |
| APP_AUTH group | websocket 同步分组之一，承载 app_auth 数据 |
| PEM | Privacy Enhanced Mail，公钥/私钥的文本编码格式 |
| 二级缓存 | 本方案中 `appKey → PublicKey` 的本地缓存，避免每请求 parsePem |

---

**文档结束。请评审后确认是否进入实现阶段（M1-M6）。**
