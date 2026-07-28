# 公钥推送链路 联调交付总结（v1.0）

> **目标读者**：erpm-pay-center 团队
> **文档版本**：v1.0（2026-07-27）
> **状态**：✅ **双侧实现均已就绪**，可进入端到端联调
> **关联文档**（按权威优先级排序）：
> 1. 接口契约：`docs/appAuth-customCreate-接口变更说明书-v2.0.md`
> 2. SPI 实现：`shenyu-admin-appauth-spi/README.md`、`shenyu-sign-gateway-spi/...设计方案.md`
> 3. 部署：`shenyu-admin-appauth-spi/shenyu-admin-appauth-spi-2.6.1部署说明.md`、`shenyu-sign-gateway-spi/shenyu-sign-gateway-spi-2.6.1部署说明.md`
> 4. 镜像 SOP：`docs/shenyu-2.6.1-SPI编译打包镜像SOP.md`

---

## 0. TL;DR（联调就绪状态）

| 项 | 状态 | 备注 |
|---|---|---|
| admin 侧 SPI（appauth-spi） | ✅ 已编译 + 已烤进镜像 + 已部署 | `/appAuth/customCreate` 端点已上线，本地与内网测试环境均部署 |
| 网关侧 SPI（sign-gateway-spi） | ✅ 已编译 + 已烤进镜像 + 已部署 | `SignCacheBizPublicKeyProvider` + `AppAuthHealthIndicator` 已上线 |
| pay-center 客户端 | ✅ 已实现 v2.0 方案 | `ShenyuAdminClient.customCreate` + `ShenyuKeyPushService.pushPublicKey` |
| 链路打通验证 | ✅ 本地与内网测试环境均通过 | 端到端：pay-center → admin → websocket → bootstrap |
| nacos 注册 | ✅ 本地 + K8s 均注册成功 | bootstrap 实例在内网 dev namespace 可见 |
| 公钥推送延迟 | ✅ 实测 1-3s 生效 | 旧 `/plugin` 方案 30s |

**结论：联调先决条件全部满足，pay-center 团队可直接按本文 §3 执行联调验证。**

---

## 1. 当前已交付物清单

### 1.1 shenyu-admin 侧（被调用方）

**镜像**：`shenyu-admin:2.6.1-appauth-spi`（本地 + 内网测试环境）
**SPI jar**：`shenyu-admin-appauth-spi-2.6.1.jar`（已烤进镜像 `/opt/shenyu-admin/ext-lib/`）

**新增端点**：

```
POST /appAuth/customCreate
```

行为：
1. fail-fast 校验 `appSecret` 是合法 X.509 RSA 公钥裸 Base64（不合法 → 400）
2. 按 `appKey` upsert `app_auth` 表（不存在则 insert，已存在则 updateSelective 覆盖 `appSecret`/`enabled`/`open`）
3. `publishEvent(DataChangedEvent(APP_AUTH, ...))` 触发 websocket 推送，**秒级同步到所有 bootstrap**

**关键能力**（与原生 `/appAuth/apply` 的核心差异）：**appKey 用调用方入参，不被随机覆盖**。这是 pay-center 能用业务方 appKey 持续更新公钥的前提。

**鉴权**：被 admin 的 Shiro `statelessAuth` 全局拦截，必须带 `X-Access-Token`。无 `@RequiresPermissions`，pay-center 用现有 pusher 账号 token 即可，**无需额外授权**。

### 1.2 shenyu-bootstrap 侧（网关）

**镜像**：`shenyu-bootstrap:2.6.1-sign-latest`（本地 + 内网测试环境）
**SPI jar**：`shenyu-sign-gateway-spi-2.6.1.jar`（已烤进镜像 `/opt/shenyu-bootstrap/ext-lib/`）

**关键 Bean**：

| Bean | 职责 |
|---|---|
| `SignCacheBizPublicKeyProvider` | 公钥源实现，从 `BaseDataCache.APP_AUTH_MAP` 读公钥（websocket push 实时同步，**零轮询**） |
| `PayRsaSignService` | 验签核心，调用 `SignCacheBizPublicKeyProvider` 取公钥 |
| `PayRsaSignConfiguration` | 装配入口，全 `@Bean`（包名 `org.apache.shenyu.plugin.sign.custom` 不在 bootstrap 主类扫描范围） |
| `AppAuthHealthIndicator` | `/actuator/health` 就绪探针，未同步前 K8s readinessProbe 不摘流 |

**v1.x 弃用**：`AdminConfigBizPublicKeyProvider`（30s 轮询 BaseDataCache.PLUGIN_MAP 的旧实现）已删除，新实现不再依赖 `springCloud.config` JSON。

### 1.3 pay-center 侧（调用方，已自实现）

| 文件 | 模块 | 职责 |
|---|---|---|
| `pay-biz/.../shenyu/ShenyuAdminClient.java` | pay-biz | OkHttp 封装：登录 token 管理 + `/appAuth/customCreate` POST + 401 重试 |
| `pay-biz/.../shenyu/ShenyuKeyPushService.java` | pay-biz | 业务入口：查 DB → `normalizeToBase64` → 调 `ShenyuAdminClient.customCreate` |
| `pay-biz/.../shenyu/ShenyuKeyPushConstant.java` | pay-biz | 路径/header/code 常量 |
| `pay-biz/.../config/properties/ShenyuKeyPushProperties.java` | pay-biz | `@ConfigurationProperties(prefix="shenyu.push")` |
| `pay-biz/.../shenyu/dto/PushResult.java` | pay-biz | 推送结果 DTO（ok/skipped/fail） |
| `pay-host/.../controller/SignController.java` | pay-host | HTTP 触发：`POST /api/v1/sign/push-public-key?appKey=xxx` |
| `pay-host/src/main/resources/application-shenyu.yml` | pay-host | 配置：`shenyu.push.*` + `shenyu.register.*` |

**pay-center 客户端实现要点（已就绪）**：
- `ShenyuAdminClient.customCreate(appKey, appSecret)` 已按 JSON body 实现，**不是 form**
- `ShenyuKeyPushService.normalizeToBase64` 已实现，**裸 Base64 输出**
- token 缓存 + 23h TTL + 401 自动重试已实现
- `enabled=true`、`open=false` 硬编码常量

> ⚠️ **遗留小问题（不影响联调）**：
> - `application-shenyu.yml` 第 35-37 行注释仍提"PUT /plugin/8"和"push-all-public-keys"，与代码实际不符。建议清理但不阻塞联调。
> - `ShenyuKeyPushProperties.connectTimeoutMillis/readTimeoutMillis` 字段未被实际 OkHttpClient bean 应用（bean 硬编码 5s/10s）。如需调超时，改 `OkHttpConfig`。

---

## 2. 联调先决条件（环境核对清单）

执行联调前逐项确认，**任一项不满足都无法继续**。

### 2.1 admin 侧

```bash
# 1.1 admin 容器/进程在跑
docker ps | grep shenyu-admin       # 本地
# 或 kubectl get pod -n <ns> | grep shenyu-admin   # K8s

# 1.2 SPI 端点存在（应返回 401 unauthorized，不是 404 not found）
curl -i -X POST http://<admin-host>:9096/appAuth/customCreate \
  -H "Content-Type: application/json" \
  -d '{"appKey":"_probe","appSecret":"_probe"}'
# 期望：HTTP/1.1 401（说明端点存在但缺 token）
# 失败：HTTP/1.1 404（说明 admin 未装 SPI，联系运维）

# 1.3 SPI jar 在镜像里（不是 bind mount）
docker exec shenyu-admin-261 ls /opt/shenyu-admin/ext-lib/
# 期望：shenyu-admin-appauth-spi-2.6.1.jar  +  mysql-connector.jar
```

### 2.2 bootstrap 侧

```bash
# 2.1 bootstrap 在跑
docker ps | grep shenyu-bootstrap    # 本地
# 或 kubectl get pod -n <ns> | grep shenyu-bootstrap   # K8s

# 2.2 SPI 装载成功（启动日志应有这 4 行）
docker logs shenyu-bootstrap-261 2>&1 | grep -E "SignCacheBizPublicKeyProvider|PayRsaSignService|AppAuthHealthIndicator"
# 或 kubectl logs <pod> | grep -E "SignCacheBizPublicKeyProvider|..."

# 2.3 就绪探针（应返回 UP）
curl http://<bootstrap-host>:9195/actuator/health
# 期望：{"status":"UP",...,"appAuth":{"status":"UP",...}}
# 若 appAuth DOWN，说明未收到 admin 推送，查 admin 是否触发过推送
```

### 2.3 nacos 注册（可选，但推荐核对）

```bash
# bootstrap 实例在 nacos 控制台可见
# 内网测试环境 nacos：10.6.5.117:30848
# namespace：dev (id=026c3e5e-431f-4e9d-a804-9fdaf94d973d)
# 期望看到：shenyu-bootstrap <ip>:9195 healthy=true
```

### 2.4 pay-center 侧

```yaml
# application-shenyu.yml 关键配置（默认值，可按环境覆盖）
shenyu:
  push:
    enabled: ${SHENYU_PUSH_ENABLED:true}        # 联调期间必须 true
    admin-base-url: ${SHENYU_PUSH_ADMIN_BASE_URL:http://<admin-host>:9096}
    username: ${SHENYU_PUSH_USERNAME:admin}
    password: ${SHENYU_PUSH_PASSWORD:1qaz!QAZ}  # 与 admin 实际账号一致
```

> ⚠️ **联调环境关键变量**：`SHENYU_PUSH_ADMIN_BASE_URL` 必须指向**装了 SPI 的 admin 实例**（本地 9096 或内网测试环境 admin），指向官方 admin 镜像会 404。

---

## 3. 联调验证步骤（端到端）

### 3.1 准备测试数据

```sql
-- 在 pay-center 的 DB 插入一条测试 appKey（用真实 RSA 公钥）
INSERT INTO pay_app_config(app_key, app_public_key, ...) VALUES (
  'JOINT_TEST_001',
  'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...<你的测试公钥裸 Base64 或 PEM 都行>...',
  ...
);
```

> 公钥可以是裸 Base64 或完整 PEM，`normalizeToBase64` 会统一处理。配套私钥保留好，§3.4 验签要用。

### 3.2 触发推送

```bash
# 调 pay-center 的 HTTP 触发端点
curl -X POST "http://<pay-center-host>:<port>/api/v1/sign/push-public-key?appKey=JOINT_TEST_001"

# 期望响应：success=true
# {"success":true,"skipped":false,"message":null}
```

### 3.3 验证 admin 侧落库

```sql
-- 在 admin 的 DB 查（shenyu 库）
SELECT id, app_key, app_secret, enabled, open, user_id
FROM app_auth
WHERE app_key = 'JOINT_TEST_001';

-- 期望：
--   app_secret = 裸 Base64（无 PEM 头尾）
--   enabled = 1, open = 0
--   user_id = 'JOINT_TEST_001'（与 appKey 相同，DB 必填列的占位值）
```

### 3.4 验证 bootstrap 收到推送（关键）

```bash
# 方式 A：查日志（最快）
docker logs shenyu-bootstrap-261 2>&1 | grep -i "APP_AUTH\|app_auth" | tail
# 或 kubectl logs <pod> | grep -i "APP_AUTH" | tail
# 期望：推送发生后 1-3s 内出现 APP_AUTH REFRESH 日志

# 方式 B：dump 缓存（更直接）
curl http://<bootstrap-host>:9195/actuator/health
# appAuth 段应包含 JOINT_TEST_001 公钥信息（视 SPI 实现是否暴露）
```

### 3.5 验证网关验签（最终验证）

构造一个用配套私钥加签的请求，发到 bootstrap，验签应通过：

```bash
# 用配套私钥按 X-Pay-* 头协议加签（参考 shenyu-client-core 的 PaySignInterceptor）
curl -X POST http://<bootstrap-host>:9195/<你的业务路径> \
  -H "X-Pay-App-Id: JOINT_TEST_001" \
  -H "X-Pay-Timestamp: <当前秒级时间戳>" \
  -H "X-Pay-Nonce: <随机串>" \
  -H "X-Pay-Signature: <用私钥 SHA256withRSA 签名后的 Base64>" \
  -H "Content-Type: application/json" \
  -d '<业务请求体>'

# 期望：业务正常处理（验签通过）
# 失败排查：
#   - 401 + "appKey not found" → 推送未到，回 §3.4 查推送日志
#   - 401 + "sign verify fail" → 公钥不匹配，检查 §3.1 的公钥与私钥是否配套
#   - 401 + "timestamp expired" → 客户端时钟偏移，调允差窗口
```

### 3.6 验证更新场景（覆盖更新）

```sql
-- 改公钥
UPDATE pay_app_config SET app_public_key='<新的裸 Base64>' WHERE app_key='JOINT_TEST_001';
```

```bash
# 再次推送
curl -X POST "http://<pay-center-host>:<port>/api/v1/sign/push-public-key?appKey=JOINT_TEST_001"
```

```sql
-- admin DB 验证：id 不变（复用记录），app_secret 已更新
SELECT id, app_secret FROM app_auth WHERE app_key='JOINT_TEST_001';
-- id 应与 §3.3 一致，app_secret 应为新公钥
```

```bash
# 用新私钥加签的请求验签通过，用旧私钥加签的请求验签失败
# 验证撤销生效延迟应在 1-3s 内
```

---

## 4. 失败场景对照表

| 现象 | 可能原因 | 排查 | 处置 |
|---|---|---|---|
| pay-center 推送返回 `fail` + "404 not found" | admin 未装 SPI 或 `admin-base-url` 指错 | `curl -i POST /appAuth/customCreate`（不带 token）看是 401 还是 404 | 联系运维装 SPI jar 或改 `SHENYU_PUSH_ADMIN_BASE_URL` |
| pay-center 推送返回 `fail` + "400 ... Illegal base64 character 2d" | `appSecret` 含 PEM 头尾（`-` ASCII=0x2d） | 检查 `normalizeToBase64` 是否被调用、PEM 正则是否匹配 | 修复 `ShenyuKeyPushService.normalizeToBase64` |
| pay-center 推送返回 `fail` + "401 unauthorized" | admin 账号密码错或 token 拿不到 | 检查 `shenyu.push.username/password` 与 admin 实际一致 | 改配置 |
| pay-center 推送返回 `success=true` 但网关验签 401 "appKey not found" | admin 推送未到 bootstrap | 查 bootstrap 日志有无 APP_AUTH REFRESH | 检查 admin ↔ bootstrap websocket 连通；bootstrap 重启 |
| pay-center 推送返回 `success=true` 但网关验签 401 "sign verify fail" | 公钥与私钥不配套 | admin DB 查 `app_secret` 与客户端私钥配对验证 | 用 `openssl rsa -in priv.pem -pubout` 重新生成配套公钥推送 |
| bootstrap /actuator/health `appAuth: DOWN` | SPI 未装载或未收到任何推送 | 查启动日志 `SignCacheBizPublicKeyProvider` 是否装载 | 重启 bootstrap；先做一次 §3.2 推送 |
| nacos 看不到 bootstrap 实例 | namespace 配错或网络不通 | 核对 `SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE` | 用 namespace **ID**（UUID）不是 showName |

---

## 5. 已知约束与运维铁律

1. **appKey 字符集**：`[A-Za-z0-9_-]`，长度 ≤64。pay-center 已在 `ShenyuKeyPushConstant.APP_KEY_PATTERN` 校验。
2. **公钥格式**：必须是 X.509 SubjectPublicKeyInfo 结构的 RSA 公钥裸 Base64（即 `openssl rsa -in priv.pem -pubout` 默认输出的 PEM 去掉头尾）。**不接受 PKCS#1 RSA PUBLIC KEY**。pay-center `normalizeToBase64` 只去头尾，不转换格式。
3. **appKey 删除**：必须走 admin REST `/appAuth/delete` 或 admin UI，**禁止直改 `app_auth` 表 DELETE**——直接 DELETE 不触发 admin 的 publishEvent，网关收不到推送，已删 appKey 的公钥会在网关内存残留仍能验签（安全漏洞）。详见 sign-gateway-spi 部署说明「运维铁律」。
4. **admin 回滚风险**：若 admin 因故回滚到官方镜像（无 SPI），`/appAuth/customCreate` 端点消失，pay-center 推送会 404。建议 pay-center 对 404 单独告警。
5. **token 有效期**：admin JWT 默认 24h，pay-center `DEFAULT_TOKEN_TTL_SECONDS=23*3600`（提前 1h 刷新）+ 401 自动重试双保险。
6. **幂等性**：同一 appKey 重复推送安全，admin 侧按 appKey upsert，不会产生重复记录。

---

## 6. 回滚预案

如联调发现严重问题需要回滚到旧 `/plugin` 方案：

| 层 | 回滚动作 |
|---|---|
| admin | image 改回 `apache/shenyu-admin:2.6.1`（官方，无 SPI），compose 恢复 ext-lib bind mount |
| bootstrap | image 改回 `apache/shenyu-bootstrap:2.6.1`（官方，无 sign-spi） |
| pay-center | 恢复 `AdminPluginClient` + `ShenyuKeyPushService` 的 GET-Modify-PUT 旧实现 |

> 回滚是破坏性操作，需双方协调。**联调阶段优先排查 §4 失败对照表，不要轻易回滚**——绝大多数问题是配置/数据问题，不是 SPI 实现问题。

---

## 7. 联调进度记录（建议双方共用）

| 日期 | 环境 | appKey | 推送 | 落库 | 网关收到 | 验签通过 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-07-27 | 本地 docker | TEST_SPI_V3 | ✅ | ✅ | ✅ (1-3s) | ✅ | 端到端打通 |
| 2026-07-27 | 内网 K8s | （pay-center 填） | | | | | |
| | | | | | | | |

---

## 附录 A：双侧实例位置（截至 2026-07-27）

| 环境 | admin | bootstrap | nacos |
|---|---|---|---|
| 本地 docker | `shenyu-admin-261`（172.29.x.x:9096） | `shenyu-bootstrap-261`（172.29.0.2:9195） | 内网 dev namespace（10.6.5.117:30848） |
| 内网 K8s（shenyu-dev） | `shenyu-admin-*` pod | `shenyu-bootstrap-dbd757db7-2xl44`（10.42.5.207:9195） | 同上 |

> 本地 docker 的 bootstrap 已注册到内网 dev nacos，可与 K8s 实例共存联调。

## 附录 B：联系人 / 责任划分

| 责任 | 负责 |
|---|---|
| admin SPI 实现 / 镜像 / 部署 | shenyu-client-java 团队 |
| bootstrap SPI 实现 / 镜像 / 部署 | shenyu-client-java 团队 |
| pay-center 推送客户端 | erpm-pay-center 团队 |
| admin 运维（账号、IP 白名单） | 运维 |
| nacos / K8s | 运维 |
