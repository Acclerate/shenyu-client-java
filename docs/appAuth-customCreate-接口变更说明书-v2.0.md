# 公钥推送接口变更说明书（v2.0：`/plugin` → `/appAuth/customCreate`）

> **目标读者**：erpm-pay-center 团队（公钥推送方 / 调用方）
> **文档版本**：v2.0（2026-07-27）
> **替代文档**：本文档替代 `docs/sign-pub-key-push-实现方案.md`（v3，描述旧 `/plugin` 链路）作为 erpm-pay-center 对接 admin 的**唯一权威接口说明**。v3 文档保留作历史背景，但其中的 `AdminPluginClient` / `gw.springcloud.app-key.*` / `springCloud.config` JSON 等描述**已废弃，不要再照此实现**。
> **配套文档**：
> - SPI 实现：`shenyu-admin-appauth-spi/README.md`
> - 部署/回滚：`shenyu-admin-appauth-spi/shenyu-admin-appauth-spi-2.6.1部署说明.md`
> - 设计背景：`shenyu-sign-gateway-spi/sign-2.6.1-appauth-实时公钥源设计方案.md`

---

## 0. TL;DR（erpm-pay-center 必须知道的 3 件事）

1. **接口换了**：从 `PUT /plugin/{id}`（改 springCloud 插件 config JSON）→ 改为 `POST /appAuth/customCreate`（直接 upsert app_auth 记录）。
2. **公钥格式变了（破坏性）**：旧接口存**完整 PEM 文本**（含 `-----BEGIN PUBLIC KEY-----` 头尾）；新接口要求**裸 Base64**（剥离 PEM 头尾和换行）。**必须新增 `normalizeToBase64` 方法处理**，否则收到 400。
3. **请求体格式变了**：旧接口 `application/x-www-form-urlencoded`（form）；新接口 `application/json`（JSON body）。token 机制（`AdminTokenManager` / `X-Access-Token` / 401 重试）**完全复用，不动**。

---

## 1. 为什么要变

### 1.1 旧方案（v3：`/plugin` config JSON）的问题

erpm-pay-center 原来的推送链路（详见 `docs/sign-pub-key-push-实现方案.md` §5）：

```
erpm-pay-center
  AdminTokenManager      ← 缓存 token，失效重试
  AdminPluginClient      ← GET /plugin/8 → 改 config JSON → PUT /plugin/8
  ShenyuKeyPushService   ← 加锁，GET-Modify-PUT 原子更新 springCloud.config
        │ HTTP（X-Access-Token）
        ▼
  ShenYu admin
  PUT /plugin/8  (springCloud)   ← 全量替换 config JSON
        │ admin 自动 websocket 同步 PLUGIN 分组
        ▼
  网关 bootstrap
  BaseDataCache.PLUGIN_MAP + 30s 轮询读取 gw.springcloud.app-key.<appKey>
```

**痛点**：

| 问题 | 影响 |
|---|---|
| 公钥塞进 `springCloud.config` JSON，**语义错位** | springCloud 插件的 config 本该放插件配置，被滥用承载公钥；JSON 越长越难维护 |
| 网关靠 **30s 轮询**读 BaseDataCache | 公钥变更最坏 30s 才生效，撤销 appKey 后有安全窗口 |
| GET-Modify-PUT **非原子** | 即便加 `ReentrantLock` 串行化，admin 侧仍可能被其他 admin UI 操作并发改 config，丢失更新 |
| 删除 appKey 靠"从 config JSON 移除字段" | 网关原生 REFRESH 是"逐条 put 覆盖不删 key"，**已删 appKey 的公钥会在网关内存永久残留仍能验签**（安全漏洞） |
| 无就绪探针 | 网关启动后、首次同步完成前，公钥缓存为空 → 全量 401 |

### 1.2 新方案（v2.0：`/appAuth/customCreate`）的改进

```
erpm-pay-center
  AdminTokenManager      ← 不变（复用）
  AdminAppAuthClient     ← 替代 AdminPluginClient，POST /appAuth/customCreate（JSON body）
  ShenyuKeyPushService   ← 简化：不再 GET-Modify-PUT，单次 POST 即 upsert + 推送
        │ HTTP（X-Access-Token）
        ▼
  ShenYu admin（需装 appauth-spi 扩展 jar）
  POST /appAuth/customCreate  ← upsert app_auth 表，admin 自动 publishEvent
        │ admin websocket 同步 APP_AUTH 分组（秒级）
        ▼
  网关 bootstrap
  SignCacheBizPublicKeyProvider（websocket push 实时更新，零轮询）
  + AppAuthHealthIndicator（/actuator/health 就绪检查，未同步前 K8s 不摘流）
```

**收益**：

- 公钥落在**专用表 `app_auth`**，语义干净；`app_secret` 列扩到 VARCHAR(4096) 承载公钥。
- 网关**秒级实时**收到 websocket push，撤销 appKey 立即生效（v2.0 SPI 的 REFRESH 会全量清空再重推，不残留）。
- POST 单次幂等 upsert（按 appKey），**无并发更新风险**，无需客户端加锁。
- 公钥前置 fail-fast 校验（X.509 RSA 裸 Base64），非法值在 admin 侧 400 拒绝，不污染下游。
- 就绪探针 `AppAuthHealthIndicator` 接入 K8s readinessProbe，未同步前 Pod 不接流量。

> **前提**：admin 侧必须已部署 `shenyu-admin-appauth-spi` 扩展 jar（见部署说明 §3 方式B）。若 admin 回滚到官方镜像，`/appAuth/customCreate` 端点消失，erpm-pay-center 调用会 404。

---

## 2. 新接口契约（`POST /appAuth/customCreate`）

### 2.1 请求

```
POST {adminBaseUrl}/appAuth/customCreate
Header:
  X-Access-Token: <admin 登录 token>          # 必填，Shiro 全局拦截
  Content-Type: application/json               # 必填，JSON body（不是 form！）
Body:
{
  "appKey":   "<指定 appKey，必填>",
  "appSecret":"<RSA 公钥裸 Base64，必填>",
  "enabled":  true,        # 可空，默认 true
  "open":     false        # 可空，默认 false
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 约束 | 语义 |
|---|---|---|---|---|
| `appKey` | String | 是 | `@NotBlank @Size(max=64)`，合法字符集 `[A-Za-z0-9_-]` | 业务方签名契约标识，对应 `pay_app_config.app_key` |
| `appSecret` | String | 是 | `@NotBlank`，必须是合法 X.509 RSA 公钥**裸 Base64** | 公钥，对应 `pay_app_config.app_public_key`（经 normalize 后） |
| `enabled` | Boolean | 否 | null=未传 | 是否启用验签；创建默认 true，更新时 null=保留现值（PATCH 语义） |
| `open` | Boolean | 否 | null=未传 | 是否开路径白名单；创建默认 false，更新时 null=保留现值 |

### 2.2 响应

**成功（HTTP 200）**：

```json
{
  "code": 200,
  "message": null,
  "data": {
    "id":     "2081544222125465600",
    "appKey": "TEST_SPI_V3"
  }
}
```

- `data.id`：app_auth 记录主键。新建时 SPI 生成 `UUIDUtils.generateShortUuid()`；更新时复用已存在记录的 id。
- `data.appKey`：回传 appKey（用于调用方确认未被随机覆盖——这是本接口相对原生 `/appAuth/apply` 的核心能力）。

**失败（HTTP 400，appSecret 非合法公钥）**：

```json
{
  "code": 400,
  "message": "appSecret is not a valid X.509 RSA public key base64: Illegal base64 character 2d",
  "data": null
}
```

> 常见触发原因：传了带 `-----BEGIN PUBLIC KEY-----` 头尾的 PEM（`-` 不是合法 Base64 字符，报 `Illegal base64 character 2d`，`2d` 是 `-` 的 ASCII 码）。**解决**：调用前用 `normalizeToBase64` 剥离 PEM 头尾。

**失败（HTTP 401，token 失效）**：见 §3.3 token 重试。

**失败（HTTP 404，admin 未部署 appauth-spi）**：admin 回滚到官方镜像，端点不存在。erpm-pay-center 应有告警，联系运维确认 admin 是否装了 SPI 扩展。

### 2.3 行为语义（upsert + 推送）

```
POST /appAuth/customCreate
  │
  ├─ 1. validateRsaPublicKeyPem(appSecret)   ← fail-fast 公钥校验
  │     非法 → 400，不落库不推送
  │
  ├─ 2. findByAppKey(appKey)
  │     ├─ 不存在 → insertSelective 创建（appKey/appSecret/enabled/open 全自控）
  │     └─ 已存在 → updateSelective 更新（appSecret/enabled/open 覆盖，appKey 不可变）
  │
  └─ 3. publishEvent(DataChangedEvent(APP_AUTH, CREATE/UPDATE, [AppAuthData]))
        → admin 的 WebsocketDataChangedListener 推送到所有 bootstrap
        → bootstrap 的 SignCacheBizPublicKeyProvider 秒级更新缓存
```

**幂等性**：同一 appKey 多次调用安全。第一次创建，后续更新（覆盖 appSecret/enabled/open）。**无需客户端加锁**（区别于旧 `/plugin` 的 GET-Modify-PUT 必须 `ReentrantLock` 串行）。

**推送延迟**：实测 admin 落库 → bootstrap 收到 websocket push 约 **1-3 秒**（旧方案 30s）。

---

## 3. erpm-pay-center 改造指引

### 3.1 token 机制（完全复用，不改）

`AdminTokenManager` / token 缓存 / 401 重试 / `X-Access-Token` header / `/platform/login?userName=` 登录 —— 这一套在新旧接口间**完全一致，不动**。详见 `docs/sign-pub-key-push-实现方案.md` §5.2（仅 token 部分仍有效）。

**登录端点（不变）**：

```
GET {adminBaseUrl}/platform/login?userName=admin&password={pwd}
```

> ⚠️ 参数名是 `userName`（驼峰），不是 `username`。token 有效期 24h，建议提前 1h 刷新。

### 3.2 推送客户端改造（`AdminPluginClient` → `AdminAppAuthClient`）

**删除**：`AdminPluginClient`（GET /plugin/{id}、PUT /plugin/{id}、form 编码、GET-Modify-PUT 逻辑）。

**新增**：`AdminAppAuthClient`，单方法 `upsertAppAuth(appKey, appSecretBareBase64, enabled, open)`，内部 `POST /appAuth/customCreate`（JSON body）。

**Java 示例（基于 OkHttp，风格与旧 `AdminPluginClient` 对齐）**：

```java
public class AdminAppAuthClient {
    private final OkHttpClient http;
    private final AdminTokenManager tokenMgr;
    private final String adminBaseUrl;
    private static final ObjectMapper M = new ObjectMapper();

    public AppAuthUpsertResp upsertAppAuth(String appKey, String appSecretBareBase64,
                                           Boolean enabled, Boolean open) {
        return executeWithRetry(() -> {
            Map<String, Object> body = new HashMap<>();
            body.put("appKey", appKey);
            body.put("appSecret", appSecretBareBase64);   // 裸 Base64，不是 PEM
            if (enabled != null) body.put("enabled", enabled);
            if (open != null) body.put("open", open);

            Request req = new Request.Builder()
                .url(adminBaseUrl + "/appAuth/customCreate")
                .header("X-Access-Token", tokenMgr.getToken())
                .post(RequestBody.create(
                    M.writeValueAsString(body),
                    MediaType.parse("application/json")))   // JSON，不是 form
                .build();
            try (Response r = http.newCall(req).execute()) {
                String json = r.body().string();
                ShenyuAdminResult result = M.readValue(json, ShenyuAdminResult.class);
                if (r.code() == 401) throw new TokenExpiredException();
                if (result.getCode() != 200) {
                    throw new RuntimeException("upsert appAuth failed: " + result.getMessage());
                }
                return M.convertValue(result.getData(), AppAuthUpsertResp.class);
            }
        });
    }

    // 401 重试模板（复用旧 AdminPluginClient.executeWithRetry 逻辑）
    private <T> T executeWithRetry(SupplierWithTokenExpired<T> action) {
        try {
            return action.get();
        } catch (TokenExpiredException e) {
            tokenMgr.invalidate();          // 清缓存
            return action.get();            // 重试一次（getToken 会重新登录）
        }
    }
}
```

### 3.3 公钥格式转换（**最关键的破坏性改动**）

**旧接口**（`/plugin` config）：存的 value 是**完整 PEM 文本**：
```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
-----END PUBLIC KEY-----
```

**新接口**（`/appAuth/customCreate`）：要求**裸 Base64**（剥离 PEM 头尾 + 所有换行）：
```
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
```

**必须在 erpm-pay-center 侧新增 `normalizeToBase64`**（网关 SPI 的 `PemUtils` 注释里明确期望调用方有这个方法）：

```java
/**
 * 把可能带 PEM 头尾/换行的公钥文本归一化为裸 Base64。
 * 处理 3 种输入：
 *   1. 完整 PEM（含 -----BEGIN/END PUBLIC KEY-----）
 *   2. 已是裸 Base64 但含换行
 *   3. 已是裸 Base64 单行（幂等，原样返回）
 */
public static String normalizeToBase64(String maybePem) {
    if (maybePem == null || maybePem.isEmpty()) {
        throw new IllegalArgumentException("public key text is empty");
    }
    String s = maybePem.trim();
    // 剥离 PEM 头尾标记（若存在）
    s = s.replaceAll("-----BEGIN [A-Z ]+-----", "")
         .replaceAll("-----END [A-Z ]+-----", "");
    // 删除所有空白（换行、空格、制表符）
    s = s.replaceAll("\\s", "");
    return s;
}
```

> **幂等保证**：已经是裸 Base64 的输入经过此方法原样返回（PEM 头尾正则不匹配、空白移除无效果），所以可对所有公钥统一调用此方法，无需先判断格式。

### 3.4 业务入口简化（`ShenyuKeyPushService`）

**删除**：`globalLock`（`ReentrantLock`）、GET config → 解析 JSON → 追加 key → 同步 pre-warm 列表 → PUT config 的整套 GET-Modify-PUT 流程。

**替换为**：单次 `adminAppAuthClient.upsertAppAuth(appKey, normalizeToBase64(pem), true, false)`。

**改造前后对比**：

| 维度 | 旧（v3 `/plugin`） | 新（v2.0 `/appAuth/customCreate`） |
|---|---|---|
| HTTP 调用次数 | 2（GET + PUT） | 1（POST） |
| 客户端锁 | 必须 `ReentrantLock` 串行 | 不需要（POST 幂等 upsert） |
| 请求体格式 | `application/x-www-form-urlencoded` | `application/json` |
| 公钥 value 格式 | 完整 PEM 文本 | 裸 Base64 |
| 失败回滚 | PUT 失败需重试或告警（config 可能半改） | POST 原子，失败即未生效，重试即可 |
| 生效延迟 | 最坏 30s（BaseDataCache 轮询） | 1-3s（websocket push） |

### 3.5 字段映射（erpm-pay-center DB → 请求体）

| 请求体字段 | 来源（`erpm_pay_center.pay_app_config`） | 处理 |
|---|---|---|
| `appKey` | `app_key` | 原值，校验字符集 `[A-Za-z0-9_-]` |
| `appSecret` | `app_public_key` | **经 `normalizeToBase64` 剥离 PEM 头尾** |
| `enabled` | 固定 `true` | 验签需启用 |
| `open` | 固定 `false` | 不开路径白名单 |

### 3.6 删除 appKey（撤销公钥）

**旧方案**：从 `springCloud.config` JSON 移除 `gw.springcloud.app-key.<appKey>` 字段 → PUT。**问题**：网关原生 REFRESH 不删 key，已删 appKey 公钥在网关内存残留仍能验签（安全漏洞）。

**新方案**：admin 原生提供 `POST /appAuth/delete` 或 admin UI 删除（删除会触发 websocket REFRESH 全量重推，网关 `SignCacheBizPublicKeyProvider` 会全量清空再重推，**不残留**）。

> ⚠️ **erpm-pay-center 不要直改 app_auth 表删除**——直接 DELETE 不触发 admin 的 publishEvent，网关收不到推送。必须走 admin REST 接口或 admin UI。详见 sign-gateway-spi 部署说明「运维铁律：禁止直改 app_auth 表」。

---

## 4. 配置变更

### 4.1 `application.yml`（erpm-pay-center）

**删除**（旧 v3 专用）：

```yaml
shenyu:
  push:
    spring-cloud-plugin-id: 8    # 不再需要，新接口不涉及 plugin id
```

**保留**（token 机制不变）：

```yaml
shenyu:
  push:
    admin-base-url: http://10.196.150.9096
    admin-username: admin
    admin-password: ${SHENYU_ADMIN_PASSWORD}    # 环境变量注入
```

### 4.2 环境变量

无新增环境变量。旧方案的 `GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS` 是**网关侧**的轮询周期，v2.0 已废弃（网关改 websocket push 零轮询），但与本接口变更无关，网关团队自行处理。

---

## 5. 验收清单（erpm-pay-center 改造后自测）

| 项 | 验证方法 | 预期 |
|---|---|---|
| 公钥格式转换 | 单测 `normalizeToBase64`：输入完整 PEM、含换行 Base64、单行 Base64 三种 | 输出均为裸 Base64 单行，且能被 `java.security.KeyFactory` 解析为 X.509 RSA PublicKey |
| 创建分支 | POST 一个 DB 不存在的 appKey | 200，`data.appKey` 回传原值（非随机） |
| 更新分支 | POST 一个已存在的 appKey，换新公钥 | 200，`data.id` 复用原记录 id，`data.appKey` 不变 |
| 公钥校验 | POST 一个 appSecret 含 `-----BEGIN-----` 头尾的 PEM | 400，`message` 含 `Illegal base64 character 2d` |
| token 失效 | 故意传过期 token | 第一次 401，自动 `invalidate` 重登，第二次成功 |
| 网关秒级生效 | POST 后立即在网关日志查 websocket push | `ShenyuWebsocketClient - handleResult({"groupType":"APP_AUTH",...,"appKey":"<你的 appKey>"})`，1-3s 内出现 |
| 网关验签 | POST 后用对应私钥发签名请求过网关 | 验签通过（HTTP 200，非 401） |
| 撤销 appKey | 走 admin UI 或 `POST /appAuth/delete` 删除 | 网关日志 1-3s 内出现 REFRESH，再次用该 appKey 签名请求 → 401 |

---

## 6. 回滚预案

### 6.1 admin 侧回滚（SPI 扩展下线）

若 admin 回滚到官方镜像（`apache/shenyu-admin:2.6.1`），`/appAuth/customCreate` 消失：

- erpm-pay-center 调用收到 **404**。
- **app_auth 表已写入的记录保留**（不影响原生功能）。
- 网关侧 `SignCacheBizPublicKeyProvider` 仍能用缓存公钥验签，直到 app_auth 数据被清空或网关重启。

### 6.2 erpm-pay-center 侧回滚（保留旧 `/plugin` 链路）

**建议**：不要回滚到旧 `/plugin` 链路。旧链路有"删除 appKey 公钥残留"安全漏洞，且 30s 延迟。若必须回滚：

1. 保留 `AdminPluginClient` 类（用 git 历史恢复）。
2. 在 `springCloud.config` 维护 `gw.springcloud.app-key.*` 双份数据（迁移期共存）。
3. 切换 `ShenyuKeyPushService` 内部委托对象（`AdminAppAuthClient` ↔ `AdminPluginClient`）。

### 6.3 推荐的灰度策略

1. **阶段 1（双写）**：erpm-pay-center 同时调 `/appAuth/customCreate` 和旧 `/plugin`，两边数据并存，网关仍读 plugin.config（30s 延迟，但稳）。
2. **阶段 2（切流）**：网关 SPI 升级到 v2.0（读 app_auth），观察 1-2 周无异常。
3. **阶段 3（下线旧链路）**：erpm-pay-center 删除 `AdminPluginClient`，停止写 plugin.config。

---

## 7. 常见问题（FAQ）

**Q1：为什么不能用 admin 原生 `/appAuth/apply` 或 `/appAuth/updateDetail`？**

A：原生接口都有关键缺陷：
- `/appAuth/apply`：硬编码 `appKey = SignUtils.generateKey()` 随机覆盖入参 appKey，且响应 `data=null` 不回传 appKey → 无法对齐 `pay_app_config.app_key` 稳定契约。
- `/appAuth/updateDetail`：要求 id 已存在，SET 子句不含 app_key，无法用指定 appKey 创建。
- `/appAuth/createOrUpdate`：未暴露为 REST；id 为空时随机覆盖 appSecret。
- `/appAuth/updateSk`：只改 appSecret，**不发 DataChangedEvent**，网关收不到推送。

详见 `shenyu-admin-appauth-spi/README.md` §1。

**Q2：appSecret 为什么必须裸 Base64，不能直接存 PEM？**

A：网关侧 `SignCacheBizPublicKeyProvider` 从 `app_auth.app_secret` 读到字符串后，直接 Base64 解码 → `KeyFactory.generatePublic(X509EncodedKeySpec)`。PEM 头尾的 `-` 不是合法 Base64 字符，会抛 `Illegal base64 character 2d`。全链路统一裸 Base64 是与网关 SPI 解析口径对齐的硬约束。`normalizeToBase64` 在 erpm-pay-center 侧做一次即可，下游不再关心格式。

**Q3：同一 appKey 高频推送（比如每分钟一次轮换公钥）安全吗？**

A：安全。每次都是 upsert，admin 侧 `findByAppKey` 命中走 updateSelective 分支（复用 id，覆盖 appSecret/enabled/open），websocket 推 UPDATE 事件。无锁、无并发问题。但建议避免无意义的重复推送（公钥未变不要推），减少 admin DB 写入和网关缓存抖动。

**Q4：推送后多久能在网关验签生效？**

A：实测 1-3 秒（admin 落库 → publishEvent → websocket push → 网关 SignCacheBizPublicKeyProvider 更新 L2 缓存）。比旧方案 30s 快一个数量级。

**Q5：admin 多实例部署时，推送到一个 admin 实例，其他网关连的别的 admin 实例能同步吗？**

A：能。admin 集群内部自己有数据同步机制（websocket/zookeeper 等，取决于 admin 配置），一个 admin 实例收到 `publishEvent` 后会广播到所有 admin，所有 admin 再各自 push 给连自己的网关。erpm-pay-center 只需保证 POST 到任意一个 admin 实例即可，**无需向所有 admin 实例分别推送**。

**Q6：`SignFacadeService#pushAllPublicKeys` facade 还要保留吗？**

A：facade 是 erpm-pay-center 内部对统一一入口（`POST /api/v1/sign/push-all-public-keys`），它的存在与否**不影响本接口契约**。建议保留 facade 作为内部统一入口，内部实现从"遍历 pay_app_config 调 AdminPluginClient"改为"遍历 pay_app_config 调 AdminAppAuthClient"。facade 的 HTTP 契约不变，调用方（如定时任务、管理后台触发）无感知。

**Q7：如果 admin 暂时不可达，erpm-pay-center 推送失败怎么办？**

A：建议加重试队列 + 告警：
1. POST 失败（网络错、5xx）→ 入本地重试队列（如 DB 表 `pay_push_retry`），定时重试。
2. 连续失败 N 次告警，人工介入。
3. admin 恢复后，队列里的待推数据会逐条成功，**网关缓存最终一致**（最坏延迟 = admin 故障时长 + 重试追平耗时）。

期间网关用旧公钥继续验签（缓存不丢），不影响存量流量。仅新增/轮换的 appKey 在推送成功前验签不可用。

---

## 8. 联系与依赖

| 角色 | 职责 | 状态 |
|---|---|---|
| erpm-pay-center 团队 | 按本文档改造推送客户端 | **待执行** |
| admin 运维 | 部署 `shenyu-admin-appauth-spi` 扩展 jar（镜像化） | ✅ 已完成（2026-07-27） |
| 网关运维 | 部署 `shenyu-sign-gateway-spi` v2.0 SPI（SignCache...Provider） | ✅ 已完成（2026-07-27） |
| DBA | `app_auth.app_secret` 列扩到 VARCHAR(4096) | 需确认（见设计方案 §DB 变更） |

> 联系对接：admin SPI 源码 `D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi`，网关 SPI 源码 `D:\privategit\github\shenyu-client-java\shenyu-sign-gateway-spi`。
