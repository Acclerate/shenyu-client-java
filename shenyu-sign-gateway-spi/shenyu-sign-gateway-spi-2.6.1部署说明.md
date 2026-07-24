# shenyu-sign-gateway-spi 部署说明

> 解法A核心工程：网关侧 RSA 验签 SPI，验签上移到 SignPlugin。
> 公钥来源 = shenyu-admin 的 `springCloud` 插件 `config` → 经 WebSocket 同步进 bootstrap 的 `BaseDataCache` map 缓存 → SPI 轮询读取。**不依赖 Redis**。

## 打包与镜像构建

### 快速构建

```bash
cd D:\privategit\github\shenyu-client-java\shenyu-sign-gateway-spi

# 1. 打 SPI jar（依赖全部 provided，产物为薄 jar）
mvn clean package
#   产物：target/shenyu-sign-gateway-spi-2.6.1.jar

# 2. 把 jar 暂存到 Dockerfile 构建上下文的 staging 目录
#    （真实 Dockerfile 位于 D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\Dockerfile，
#     其 COPY 源是相对该目录的 /shenyu-bootstrap/ext-lib/shenyu-sign-gateway-spi-2.6.1.jar）
cp target/shenyu-sign-gateway-spi-2.6.1.jar \
   D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-bootstrap\ext-lib\

# 3. 在 shenyu-2.6.1 目录下构建镜像（build context 根 = 该目录）
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1
docker build -t shenyu-bootstrap:2.6.1-sign-latest --build-arg BUILD_DATE="$(date +%FT%T)" .
```

**产物**：
- `target/shenyu-sign-gateway-spi-2.6.1.jar`（SPI 主 jar；依赖全部 `provided`，由 bootstrap 容器提供，无需 shade）
- `shenyu-bootstrap:2.6.1-sign-latest`（Docker 镜像，与 docker-compose 引用的 tag 一致）

**Dockerfile 集成机制**：
- `FROM apache/shenyu-bootstrap:2.6.1`，`COPY /shenyu-bootstrap/ext-lib/shenyu-sign-gateway-spi-2.6.1.jar /opt/shenyu-bootstrap/ext-lib/`
- SPI 通过 `META-INF/spring.factories` 声明的 `PayRsaSignConfiguration`（`@Configuration`）自动装配，`ext-lib/` 由 ShenYu 的 `ShenyuLoaderService` 加载。实测运行正常、日志干净（无 `ApplicationContext has not been refreshed yet` 噪声）。
- 注意：jar 必须先暂存到 `shenyu-bootstrap/ext-lib/`（Dockerfile 的 COPY 源），构建从 `shenyu-2.6.1/` 目录执行。

## 部署到 Docker 网关

### 镜像化部署（推荐，K8s 迁移友好）

> SPI jar 打进镜像，消除 ext-lib bind mount 依赖，避免 K8s `subPath` 挂载陷阱和 PVC 拓扑强绑定（详见 Pod 漂移分析文档）。

**1. docker-compose.yaml 关键片段**

```yaml
services:
  shenyu-bootstrap:
    image: shenyu-bootstrap:2.6.1-sign-latest   # 自定义镜像（SPI 已烤进 ext-lib/）
    volumes:
      - "./shenyu-bootstrap/conf:/opt/shenyu-bootstrap/conf"
      - "./shenyu-bootstrap/logs:/opt/shenyu-bootstrap/logs"
      # 无需 ext-lib bind mount，也无需任何 Redis 相关配置
    environment:
      - TZ=Asia/Shanghai
      - shenyu.sync.websocket.urls=ws://shenyu-admin-261:9095/websocket
      - GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS=30   # BaseDataCache 轮询周期（秒，下限 5）
```

**2. 启动/重启（同 tag 重建镜像后必须 force-recreate）**

```bash
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1
docker compose -f docker-compose-ShenYu.yaml up -d --force-recreate shenyu-bootstrap
```

### 公钥来源：ShenYu 插件数据（BaseDataCache）

`PayRsaSignService` 通过 `AdminConfigBizPublicKeyProvider` 取公钥，公钥源 = admin 的 `springCloud` 插件 `config`：

- SPI 读取 `BaseDataCache.getInstance().obtainPluginData("springCloud").getConfig()`（JSON）。
- 解析顶层 key `gw.springcloud.app-key.<appKey>` = PEM 公钥，构建 `appKey → PublicKey` 映射。
- 后台守护线程 `gw-sign-adminconfig-refresh` 每 `GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS`（默认 30s，下限 5s）轮询一次，`volatile + unmodifiableMap` 原子发布；热路径 `currentKey(appKey)` 仅 `volatile 读 + HashMap.get`，零 I/O 零锁。
- **优雅降级**：config 为 null/空、非法 JSON、或解析出 0 条有效公钥时，保留旧缓存（不放大为全站 401）。撤销某 appKey 后，下一次有效同步自动移除（最长 30s 延迟）。

**环境变量**：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS` | `30` | BaseDataCache 轮询周期（秒，下限 5） |

> 说明：本方案**没有任何 `GW_SIGN_REDIS_*` / `GW_SIGN_KEY_SOURCE` 环境变量**。公钥全部来自 admin 下发，无需在 bootstrap 侧配置任何公钥来源。

### 公钥注入（写入 admin plugin.config → 同步进 BaseDataCache）

公钥经 admin 落库到 `plugin(name='springCloud').config`，键名 `gw.springcloud.app-key.<appKey>`，值为 PEM。两种注入方式：

**方式1：erpm-pay-center 推送（生产链路）**
- 调 `POST /api/v1/sign/push-all-public-keys`（`SignFacadeService#pushAllPublicKeys`）。
- 内部从 `erpm_pay_center.pay_app_config` 读 `app_key` + `app_public_key`，经 admin REST（GET/PUT `/plugin`）写入 `springCloud.config`。

**方式2：直改数据库（本地测试）**
```sql
-- 先 SELECT 现有 config 合并，勿覆盖已有字段
UPDATE shenyu_261.plugin
SET config='{...,"gw.springcloud.app-key.biz001":"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"}'
WHERE name='springCloud';
```
> 直改 DB 后需 `docker compose ... restart shenyu-admin`，admin 重启从 DB 重载 → WebSocket 下发 → bootstrap `BaseDataCache` 更新（≤30s 生效）。

### 启动验证

```bash
docker logs -f shenyu-bootstrap-261 | grep -i "GW-Sign"
# 期望：
# 1. [GW-Sign] 同步完成：N 条公钥生效
# 2. [GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService
# 且无 "ApplicationContext has not been refreshed yet" 噪声（证明 jar 在 lib/）
```

### Admin 后台启用 sign 插件

1. 打开 http://localhost:9096，登录 admin / 1qaz!QAZ
2. 插件管理 → `sign` 插件 → 启用
3. sign 选择器：匹配业务路径（如 `/springcloud-demo/**`）
4. sign 规则：启用签名校验

## 签名协议

| 请求头 | 说明 |
|---|---|
| `X-Pay-App-Key` | appKey（对应 `gw.springcloud.app-key.<appKey>`） |
| `X-Pay-Timestamp` | epoch 毫秒；网关 ÷1000 比对当前秒，容差 ±300s |
| `X-Pay-Nonce` | 随机串 |
| `X-Pay-Sign` | `SHA256withRSA` 签名 → 标准 Base64（单行） |

待签名串（5 行，每行以 `\n` 结尾）：`METHOD\nURL\nTS\nNONCE\nBODY\n`（URL=网关收到的原始路径，带 contextPath、GET 含 query；GET 的 BODY 段为空串）。

> **重要（实测）**：当前 ShenYu sign 插件在 body 被 WebFlux 完全缓存前即调用 `PayRsaSignService.signatureVerify(exchange)`，传入的 `requestBody` 始终为空串。因此**无论 GET 还是 POST，签名串的 BODY 段都按空串处理**。客户端对 POST 请求也用空 BODY 段加签（实际请求体照常发送）。判定与排查以 `X-Pay-Sign` 对应的签名串第 5 行为空为准。

## 故障降级行为

| 场景 | 行为 | 验证结果 |
|---|---|---|
| **admin 同步正常** | BaseDataCache 命中，30s 内公钥生效 | 正常 |
| **plugin.config 无该 appKey** | 热路径 `currentKey` 未命中 → 验签失败 | HTTP 401 |
| **config 为空/非法 JSON/0 条有效公钥** | 保留旧缓存（优雅降级），不放大为全站 401 | 用旧公钥继续验签 |
| **admin/WebSocket 故障** | BaseDataCache 保留旧值兜底 | 用旧值继续，重连后自动刷新 |
| **cacheMap 尚未初始化** | 首次同步未完成 | HTTP 401（provider not initialized） |

**关键点**：
- admin 不可达 **不会导致网关启动失败**（SPI 构造期不阻塞，后台线程异步同步）。
- 健康检查依赖 `/actuator/health`，而非依赖 admin 可达性。

## 回滚流程

### 镜像回滚

```bash
docker tag shenyu-bootstrap:2.6.1-sign-20260701-120000 shenyu-bootstrap:2.6.1-sign-latest
docker compose -f docker-compose-ShenYu.yaml up -d --force-recreate shenyu-bootstrap
```

### 公钥回滚

重新调用推送接口，或直改 DB 覆盖 `plugin(springCloud).config` 中对应 `gw.springcloud.app-key.<appKey>`，再 `restart shenyu-admin` 触发同步。

## 解法A原理

```
插件执行顺序（切勿调乱）：
  SignPlugin(50) → RequestPlugin(100) → ContextPathPlugin(150) → DividePlugin(200)

SignPlugin 执行时：
  - 路径还是网关收到的原始路径：/springcloud-demo/order/findById?id=1（带 contextPath）
  - 与业务方加签时用的路径完全一致 → 验签天然匹配

ContextPathPlugin 执行时（SignPlugin 之后）：
  - 剥离 contextPath → 后端收到 /order/findById
  - 验签已在 SignPlugin 完成，后端无需再验签
```

---

# v2.0 部署补充（app_auth 数据源，2026-07-24）

> **本章节内容覆盖上文 v1.x 的"公钥注入方式"与"公钥回滚"描述。**
> v2.0 起，公钥数据源从 `plugin.config` JSON 切换为 `app_auth` 表，SPI 通过 websocket push 实时接收，零轮询。

## 数据源切换

| 维度 | v1.x（plugin_config） | v2.0（app_auth） |
|---|---|---|
| 公钥存储 | `plugin(name='springCloud').config` 的 `gw.springcloud.app-key.<appKey>` | `app_auth.app_secret`（VARCHAR 扩到 4096） |
| 同步通路 | PLUGIN group | **APP_AUTH group** |
| 网关侧缓存 | `BaseDataCache.PLUGIN_MAP` + 30s 轮询 | `SignCacheBizPublicKeyProvider` 自建 `ConcurrentHashMap`，websocket push 秒级实时 |
| 就绪检查 | 无 | `AppAuthHealthIndicator` + K8s readinessProbe |
| 实时性 | 30s 延迟 | 秒级（websocket 推送延迟） |

## 数据库变更（前置，DBA 执行）

执行 `db/upgrade/2.6.1-app-auth-app-secret-to-4096-mysql.sql`：
```sql
ALTER TABLE `app_auth` MODIFY COLUMN `app_secret` VARCHAR(4096)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
  COMMENT '验签凭证（承载 RSA 公钥 PEM）';
```
> ⚠️ 勿误改 `alert_receiver.app_secret`（无关表）。

## ⚠️ 运维铁律：禁止直改 app_auth 表

**所有 app_auth 表的增删改（新增/修改公钥、启用/禁用、删除 appKey）必须通过 ShenYu Admin REST API 进行，严禁任何形式的直接数据库操作（SQL、客户端工具）。**

**原因**：直接改库不触发 admin 的 `publishEvent`，网关侧缓存保持陈旧。更严重的是——即便事后触发全量同步（`syncData`），ShenYu 原生 REFRESH 是"逐条 put 覆盖，不删 key"，**已删除的 appKey 公钥会在网关内存里永久残留，仍能验签通过，直到网关重启**。这是安全漏洞。

**后果**：违反此约束的唯一恢复方法是重启所有 ShenYu 网关实例清空内存。

## 公钥写入正确姿势（运维 / erpm-pay-center）

| 接口 | 方法 | 评估 |
|---|---|---|
| `/appAuth/apply` | POST | ❌ 会用 `SignUtils.generateKey()` 覆盖 appSecret 为随机 UUID |
| `/appAuth/createOrUpdate` | POST | ⚠️ id 为空时也会覆盖 |
| **`/appAuth/updateDetail`** | POST AppAuthDTO | ✅ **推荐**，JSON body 可放任意长 PEM |
| `/appAuth/updateSl` | GET | ❌ PEM 的 `\n`/`=`/`+` 在 URL 需编码 |

**工作流**：先 `POST /appAuth/apply` 拿 appKey → 再 `POST /appAuth/updateDetail` 把 PEM 写进 appSecret 覆盖随机值 → admin 自动 websocket 推送到网关。

## K8s 就绪探针配置（新增）

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 9195
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
```

**工作原理**：
1. Pod 启动，websocket 尚未同步 → `AppAuthHealthIndicator` 返回 DOWN（`everSynced=false`）
2. K8s 探针失败，Pod 不 Ready，不接流量
3. admin 完成首次推送 → `everSynced=true` → HealthIndicator 返回 UP
4. Pod Ready，开始接流量

> 注：`everSynced` 首次置 true 后永不变 false（即便全量 REFRESH 清空数据源的亚秒级窗口内仍 true），避免 K8s 误摘流。详见设计方案 §D2。

## 环境变量变更

| 变量 | v1.x | v2.0 |
|---|---|---|
| `GW_SPRINGCLOUD_REFRESH_INTERVAL_SECONDS` | 30（轮询周期） | **已废弃**（零轮询，删除） |
| `shenyu.sync.websocket.urls` | ws://admin/websocket | 不变 |
| `shenyu.plugins.sign.enabled` | 默认 true | **必须保持 true**（禁用则缓存不填充，方案失效） |

## 公钥回滚（v2.0）

由于旧实现已删除，回滚**不支持运行时切换数据源**，改用镜像版本回退：

1. **L1（推荐）**：docker-compose 切回旧镜像 tag（内含 v1.x SPI jar，读 `plugin.config`）。前提：迁移期间在 `plugin.config` 保留 `gw.springcloud.app-key.*` 双份数据。
2. **L2（兜底）**：DDL 回列到 VARCHAR(128)，但回滚前必须清空所有 PEM 公钥（否则截断）。

## 滚动更新流程

1. 发布含 v2.0 SPI 的镜像
2. K8s 逐个启动新 Pod，新 Pod 因 readinessProbe 失败保持 NotReady
3. admin 完成数据推送（秒级到十几秒）
4. 新 Pod Ready，接流量；K8s 终止旧 Pod
