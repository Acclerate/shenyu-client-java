# sign-gateway-spi 公钥源 Redis → HTTP 改造计划

> 状态：计划已定稿（grilling 14 项决策全部确认），待实施。
> 目标：把 ShenYu 网关侧 `shenyu-sign-gateway-spi` 的「业务方验签公钥」获取源从 Redis 改为
> 调用 `shenyu-springcloud-demo` 提供的 HTTP 接口，保持多租户能力 + 三级兜底不变。

---

## 0. 目标与背景

当前 `shenyu-sign-gateway-spi`（解法 A：网关侧验签）从 Redis 读取业务方（`X-Pay-App-Key`）对应的 RSA 公钥，
用于校验入站请求的 `X-Pay-Sign`。本次改造把公钥源从 Redis 换成 HTTP：
由 `shenyu-springcloud-demo`（端口 8470）暴露一个 `GET /sign/public-key/{appKey}` 接口，
SPI 用 Apache HttpClient 直连 demo 拉取公钥。

**为什么要改**：去掉对 Redis 这一独立中间件的强依赖，公钥与业务服务同源管理，配合密钥轮换更简单。

**关键约束**：
- ShenYu `bootstrap` 镜像是 **JDK 8 + WebFlux 反应式**栈，但本 SPI 是**同步**风格（lettuce sync / synchronized），
  改造后延续同步 + Apache HttpClient（镜像 `lib/` 内已有 `httpclient-4.5.14.jar` + `httpcore-4.4.16.jar`，用 `provided`）。
- JSON 解析用 `gson`（镜像内已有 `gson-2.9.0.jar`），**不引 Jackson**（避免与 WebFlux 体系冲突）。
- demo 的验签公钥接口**必须绕过网关 sign 插件**（否则循环验签），手段是：不标 `@ShenyuSpringCloudClient`，
  直接走 demo 自身 8470 端口暴露给 SPI 直连。

---

## 1. grilling 决策清单（14 项，全部确认）

| # | 决策点 | 选择 |
|---|--------|------|
| 1 | demo 角色定位 | demo 双角色（业务 + 密钥），SPI 直连 demo IP |
| 2 | HTTP 接口 URL 形式 | `GET /sign/public-key/{appKey}`（RESTful 路径变量） |
| 3 | 响应体格式 | JSON 包裹（含 `publicKey` + `fingerprint` + `version` 等元数据） |
| 4 | HTTP 客户端 | Apache HttpClient（同步，bootstrap 镜像 `lib/` 已提供） |
| 5 | 失败兜底 | HTTP 失败 → stale 旧缓存 → classpath PEM（三级兜底，与现有 Redis 设计对等） |
| 6 | classpath PEM 来源 | SPI jar 内嵌 `biz-public-key.pem` |
| 7 | appKey 数据源 | demo classpath 加载 PEM 映射（零 DB） |
| 8 | Redis 代码去留 | 完全替换，重写为 HTTP，删 Redis 相关代码 |
| 9 | 镜像构建方式 | 继续 stock 镜像 + ext-lib bind mount（`mvn package` → `cp jar` → `docker restart`） |
| 10 | 配置命名 | `gw.sign.http.*` 系列（与 Redis 系列并存互不干扰） |
| 11 | 测试覆盖 | 两侧都加完整单测 |
| 12 | 端口修复 | demo `application.yml` 改回 8470（与文档/`.http` 对齐） |
| 13 | PEM 密钥对 | 生成全新 RSA 密钥对放 demo（不与现有 `biz001` 互通） |
| 14 | appKey 映射初始化 | `@ConfigurationProperties` 加载 yml 映射 |

---

## 2. 现状事实核实（实测，已确认）

| 项 | 现状 | 来源 |
|----|------|------|
| SPI Redis 实现 | `DynamicBizPublicKeyProvider` 约 300 行，用 `io.lettuce.core.RedisClient`，Redis 相关代码占大头（连接、`tryReconnect` 定时器、`buildRedisKey`） | 已读源码 |
| 接口稳定性 | `BizPublicKeyProvider` 接口极简：`PublicKey currentKey(String appKey)`，改造后**接口不变** | 已读源码 |
| 调用点 | `PayRsaSignService.signatureVerify` 调用 `bizPublicKeyProvider.currentKey(appKey)` 做 `SHA256withRSA` 验签 | 已读源码 |
| 配置装配 | `PayRsaSignConfiguration` 用 `Environment.getProperty` 读 `gw.sign.*` 系列，构造 provider 与 `SignService` Bean | 已读源码 |
| SPI 依赖 | `pom.xml` 含 `lettuce-core:6.3.2.RELEASE`（provided） | 已读源码 |
| SPI 内 PEM | `src/main/resources/` 下**仅有** `META-INF/spring.factories`，**无** `biz-public-key.pem`（三级兜底最后一环当前会失败） | Glob 确认 |
| demo 端口 | `application.yml` 实际 `server.port=8471`；README / `.http` 写 `8470`。**本次改回 8470** | 已读 yml + `.http` |
| demo 端口监听 | 实测 8470 与 8471 都在监听（历史遗留多实例），改造后统一 8470 | 前序 Explore |
| 镜像内 jar | `httpclient-4.5.14.jar` + `httpcore-4.4.16.jar` + `gson-2.9.0.jar` 已在 bootstrap 镜像 `lib/` | 前序 Explore |
| demo PEM 现状 | `shenyu-sign-demo-biz` 里**只有** `biz-private-key.pem`，无 `biz-public-key.pem`（公钥由私钥派生）。故本次**生成全新密钥对** | 前序 Explore |
| 注册机制 | `SpringCloudClientEventListener` 只扫 `@ShenyuSpringCloudClient`；不标该注解的 Controller **不会被注册到 admin**，但 demo 自身端口直连可达 | 前序 Explore |

**可复用的好代码**（HTTP 版应保留）：
`DynamicBizPublicKeyProvider` 中的 `CachedEntry`、`currentKey` 的 DCL 双检 + per-appKey 锁、`refresh` 的 stale/classpath 兜底、`parsePem`、`readClasspathPem`、`close`。这些与「源」无关，抽出来给 `HttpBizPublicKeyProvider` 复用。

---

## 3. 架构

```
   客户端 ──► 网关 bootstrap:9196 ──► SignPlugin(50) ──► PayRsaSignService
                                                          │
                                                          ▼
                                              HttpBizPublicKeyProvider
                                                  │ 1. 内存缓存（TTL=30s）
                                                  │ 2. 缓存 miss → HTTP GET
                                                  │ 3. HTTP 失败 → stale 旧缓存
                                                  │ 4. stale 也失败 → classpath PEM
                                                  ▼
                          Apache HttpClient ──► demo:8470/sign/public-key/{appKey}
                                                  │
                                                  ▼
                                              SignController
                                                  │
                                                  ▼
                                      PublicKeyStore (@ConfigurationProperties)
                                                  │ 启动时从 yml 加载 appKey → PEM 文件名映射
                                                  │ 启动时从 classpath:keys/*.pem 读入内存
                                                  ▼
                                          内存 Map<appKey, PublicKeyInfo>
```

**网络关键**：SPI 配置 `gw.sign.http.base-url=http://10.19.236.150:8470`（宿主机网卡 IP，容器内能达）。
demo 的验签接口不走网关（不标 `@ShenyuSpringCloudClient`），SPI 直连 demo IP，避免循环依赖。

---

## 4. HTTP 接口契约

**请求**：`GET /sign/public-key/{appKey}`
- 例：`GET /sign/public-key/biz001`
- Header：`Accept: application/json`、`X-Source: shenyu-sign-spi`

**响应 200**（Content-Type: application/json）：

```json
{
  "appKey": "biz001",
  "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...\n-----END PUBLIC KEY-----",
  "algorithm": "RSA",
  "format": "X.509",
  "fingerprint": "sha256:a1b2c3d4...",
  "retrievedAt": 1784510000000
}
```

**响应 404**：appKey 不存在

```json
{ "error": "app_key_not_found", "appKey": "xxx", "message": "..." }
```

**响应 500**：内部错误（PEM 解析失败等）

---

## 5. SPI 改造方案（`shenyu-sign-gateway-spi/`）

### 5.1 文件清单

| 文件 | 操作 | 内容 |
|------|------|------|
| `pom.xml` | 改 | 删 `io.lettuce:lettuce-core`；加 `org.apache.httpcomponents:httpclient:4.5.14`（scope=provided）；加 `com.google.code.gson:gson:2.9.0`（scope=provided） |
| `BizPublicKeyProvider.java` | 保留 | 接口不变 |
| `DynamicBizPublicKeyProvider.java` | 删除 | Redis 实现整体废弃 |
| `HttpBizPublicKeyProvider.java` | 新建 | HTTP 实现，复用同样的缓存/stale/classpath 兜底逻辑，把 Redis I/O 换成 HttpClient |
| `PayRsaSignConfiguration.java` | 改 | key-source 选项从 `redis` → `http`；读取 `gw.sign.http.*` 系列；Bean 类型改为 `HttpBizPublicKeyProvider` |
| `src/main/resources/biz-public-key.pem` | 新建 | 兜底用，放与 demo 同一对 RSA 密钥的公钥部分 |
| `src/test/java/.../HttpBizPublicKeyProviderTest.java` | 新建 | 单测（覆盖 200/404/500/超时/stale/classpath 兜底） |
| `docker-compose-shenyu-bootstrap-patch.yaml` | 改 | `GW_SIGN_REDIS_*` → `GW_SIGN_HTTP_*` |
| `scripts/init-redis-public-key.sh` | 删除 | Redis 写入脚本废弃 |
| `shenyu-sign-gateway-spi-2.6.1部署说明.md` | 改 | 文档全面更新为 HTTP 模式 |

### 5.2 HttpBizPublicKeyProvider 关键设计

```java
public final class HttpBizPublicKeyProvider implements BizPublicKeyProvider, AutoCloseable {

    // 复用自原 DynamicBizPublicKeyProvider（与源无关）：
    //   CachedEntry / currentKey DCL双检 / per-appKey 锁 / refresh stale+classpath 兜底
    //   / parsePem / readClasspathPem / close

    private final CloseableHttpClient httpClient;        // PoolingHttpClientConnectionManager
    private final String baseUrl;                        // gw.sign.http.base-url
    private final String pathPattern;                    // gw.sign.http.path-pattern ("/sign/public-key/%s")
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Set<String> knownAppKeys;             // 来自配置的 appKey 集合，供后台刷新遍历
    private volatile boolean sourceAvailable = true;
    private ScheduledExecutorService reconnectTimer;     // 失败 60s 重连探测（保留 tryReconnect 思想）
    private ScheduledExecutorService refreshScheduler;   // 后台主动刷新（见 §5.5，把网络 I/O 移出热路径）
}
```

**热路径零阻塞（关键）**：`currentKey(appKey)` 在网关 SignPlugin 的 **Netty EventLoop 线程**上被**同步**调用
（字节码已证实 `doExecute`→`rewriteRequestBody().flatMap`→`signatureVerify`，无 `publishOn`/`subscribeOn`），
因此**绝不能在该线程上做同步 HTTP I/O**。本设计把网络调用彻底移出热路径（详见 §5.5）：

1. **后台主动刷新**：`refreshScheduler` 每 `TTL/2`（默认 15s）遍历 `knownAppKeys`，在**守护线程**上
   `httpClient.execute()` 拉取各 appKey 的公钥并写入缓存（带 stale 兜底）。
2. **热路径只做缓存读**：`currentKey` 命中缓存即返回（纯内存读，非阻塞，不在 EventLoop 上触网）。
3. **兜底按需拉取（极少触发）**：仅当缓存未命中（全新 appKey / demo 上次刷新时宕机）才在热路径上
   通过独立 `ExecutorService` 做一次 `future.get()` 阻塞拉取（复用 per-appKey 锁防并发击穿）。
   这是唯一会短暂阻塞 EventLoop 的场景，频率 ≤ 每 TTL 一次/key，属可接受范围。

**复用原则**：把 `parsePem` / `readClasspathPem` / `CachedEntry` / `currentKey` / `refresh` 的兜底骨架从旧类抽进新类，
只把 `readPem(appKey)` 的 Redis 分支替换为 HTTP 分支，行为 1:1 对等。

### 5.3 配置 key 字典（gw.sign.http.*）

| key | env | 默认值 | 含义 |
|-----|-----|--------|------|
| `gw.sign.key-source` | `GW_SIGN_KEY_SOURCE` | `classpath` | 选 `http` 启用 HTTP 模式 |
| `gw.sign.classpath-public-key` | `GW_SIGN_CLASSPATH_PUBLIC_KEY` | `biz-public-key.pem` | 兜底 PEM |
| `gw.sign.http.base-url` | `GW_SIGN_HTTP_BASE_URL` | `http://127.0.0.1:8470` | demo 根 URL |
| `gw.sign.http.path-pattern` | `GW_SIGN_HTTP_PATH_PATTERN` | `/sign/public-key/%s` | URL 模板，`%s` 填 appKey（URL-encode） |
| `gw.sign.http.connect-timeout-ms` | `GW_SIGN_HTTP_CONNECT_TIMEOUT_MS` | `1000` | 连接超时 |
| `gw.sign.http.read-timeout-ms` | `GW_SIGN_HTTP_READ_TIMEOUT_MS` | `2000` | 读超时 |
| `gw.sign.http.max-connections` | `GW_SIGN_HTTP_MAX_CONNECTIONS` | `20` | 连接池大小 |
| `gw.sign.http.refresh-interval-seconds` | `GW_SIGN_HTTP_REFRESH_INTERVAL_SECONDS` | `15` | 后台主动刷新间隔（建议 = cache TTL 的一半） |
| `gw.sign.cache.ttl-seconds` | `GW_SIGN_CACHE_TTL_SECONDS` | `30` | 本地缓存 TTL |
| `gw.sign.cache.failure-retry-seconds` | `GW_SIGN_CACHE_FAILURE_RETRY_SECONDS` | `3` | 失败短重试窗口 |
| `gw.sign.cache.allow-stale-on-refresh-failure` | `GW_SIGN_CACHE_ALLOW_STALE_ON_REFRESH_FAILURE` | `true` | stale 开关 |

### 5.4 PayRsaSignConfiguration 改动要点

- 删除 `gw.sign.redis.*` 的 7 个 `getString/getInt/getLong` 调用。
- 新增 `gw.sign.http.*` 的读取（base-url / path-pattern / connect-timeout-ms / read-timeout-ms / max-connections）。
- `bizPublicKeyProvider` Bean 类型由 `DynamicBizPublicKeyProvider` 改为 `HttpBizPublicKeyProvider`，
  注入上述 http 配置 + classpath 兜底配置 + cache 配置。
- `signService` Bean 不变（仍 `new PayRsaSignService(bizPublicKeyProvider)`）。

### 5.5 线程模型：规避 WebFlux EventLoop 阻塞（评审补充修正）

**背景**：评审（文档末尾附录）指出 Apache HttpClient 同步调用会阻塞 WebFlux 的 Netty EventLoop 线程，
并建议用 `ExecutorService` + `future.get()` 包裹。经字节码核实，`SignPlugin.doExecute` 通过
`ServerWebExchangeUtils.rewriteRequestBody(...).flatMap(lambda$doExecute$0)` 调用
`SignService.signatureVerify`，**全程无 `publishOn`/`subscribeOn`**，故 `signatureVerify` 确在
EventLoop 线程上同步执行。

**对评审方案的修正**：评审建议的 `future.get()` 写法**不足以释放 EventLoop**——
`currentKey` 由 EventLoop 线程同步调用，`future.get()` 只会让 EventLoop 线程自身 park 等待，
socket I/O 虽挪到 worker 线程，但 EventLoop 仍被占用。这与现有 Redis 版（`lettuce sync .get()`）
是同源的潜在问题，并非 HTTP 改造引入的新回归。

**本计划采用的彻底解法（把网络 I/O 移出热路径）**：

| 机制 | 线程 | 是否阻塞 EventLoop |
|------|------|--------------------|
| 后台主动刷新（`refreshScheduler`，每 15s 遍历 knownAppKeys 拉取） | 守护线程 | 否 |
| 热路径 `currentKey` 缓存命中读 | EventLoop | 否（纯内存读） |
| 兜底按需拉取（仅缓存 miss，经 `ExecutorService`+`future.get()`） | EventLoop（短暂 park） | 仅 miss 时极短暂 |

**实施要点**：
- 构造时启动 `refreshScheduler`（守护线程，`scheduleWithFixedDelay`，周期 = `refresh-interval-seconds`），
  遍历 `gw.sign.http.known-app-keys`（或在首次成功响应时收集 appKey）调用 `fetchPem` 并写入缓存。
- `currentKey` 优先返回缓存；仅在缓存缺失时走一次 `ExecutorService` 兜底拉取（复用 per-appKey 锁 + stale/classpath 三级兜底）。
- 关闭时 `refreshScheduler.shutdownNow()` + `reconnectTimer.shutdownNow()` + `httpClient.close()`。

> 该机制同时消除了现有 Redis 版的同源阻塞隐患，是本次重写顺带的生产级加固。

---

## 6. demo 改造方案（`shenyu-springcloud-demo/`）

### 6.1 文件清单

| 文件 | 操作 | 内容 |
|------|------|------|
| `src/main/resources/application.yml` | 改 | 端口 `8471` → `8470`；加 `sign.public-key-store.*` 配置 |
| `src/main/resources/keys/biz-public-key.pem` | 新建 | 新生成 RSA 公钥 |
| `src/main/resources/keys/biz-private-key.pem` | 新建 | 配套私钥（仅 demo 自签名自验证测试用，不暴露接口） |
| `src/main/java/.../sign/SignController.java` | 新建 | `GET /sign/public-key/{appKey}`，标 `@RestController` **不标** `@ShenyuSpringCloudClient` |
| `src/main/java/.../sign/PublicKeyStore.java` | 新建 | `@ConfigurationProperties(prefix="sign.public-key-store")`，启动时加载 appKey → PEM 文件 → `PublicKeyInfo` |
| `src/main/java/.../sign/PublicKeyInfo.java` | 新建 | DTO（appKey/publicKey/algorithm/format/fingerprint/retrievedAt） |
| `src/main/java/.../sign/SignProperties.java` | 新建 | `@ConfigurationProperties` 配置类（appKey → 文件名映射） |
| `src/test/java/.../sign/SignControllerTest.java` | 新建 | MockMvc 单测（正常 / appKey 不存在 / JSON 格式 / fingerprint 正确 / 路径变量） |
| `src/test/java/.../sign/PublicKeyStoreTest.java` | 新建 | 启动加载逻辑测试 |
| `src/main/resources/http/shenyu-springcloud-demo.http` | 改 | 追加 `/sign/public-key/*` 端到端用例 |
| `README.md` | 改 | 加「公钥服务接口」章节，更新端口为 8470 |

### 6.2 SignController 关键设计

```java
@RestController
@RequestMapping("/sign/public-key")
// 注意：不标 @ShenyuSpringCloudClient，避免被网关 sign 插件循环验签
// 该接口直接通过 demo 的 8470 端口暴露给 SPI 调用（不经网关）
public class SignController {

    private final PublicKeyStore publicKeyStore;

    @GetMapping("/{appKey}")
    public ResponseEntity<PublicKeyInfo> getPublicKey(@PathVariable String appKey) {
        PublicKeyInfo info = publicKeyStore.get(appKey);
        if (info == null) {
            return ResponseEntity.status(404)
                    .body(PublicKeyInfo.error("app_key_not_found", appKey, "no public key for appKey"));
        }
        return ResponseEntity.ok(info);
    }
}
```

**不走 ShenYu 注册的事实依据**：`SpringCloudClientEventListener.getAnnotationType()` 只扫 `@ShenyuSpringCloudClient`；
不带该注解的 bean 不会被注册到 admin。SignController 标 `@RestController` 但不标该注解 → 网关不转发，
但 demo 自身 8470 端口直连可达 → SPI 用 HttpClient 直连 `demo:8470` 完全可行。

### 6.3 PublicKeyStore / SignProperties 设计

```java
@ConfigurationProperties(prefix = "sign.public-key-store")
public class SignProperties {
    // appKey -> classpath 下 PEM 文件名（默认 keys/biz-public-key.pem）
    private Map<String, String> keys = new HashMap<>();
    // getters/setters
}

@Component
public class PublicKeyStore {
    private final Map<String, PublicKeyInfo> store = new ConcurrentHashMap<>();

    public PublicKeyStore(SignProperties props) {
        // 启动时遍历 props.getKeys()：
        //   ClassPathResource("keys/" + fileName) → 读 PEM → parsePem → 算 fingerprint → 存 Map
    }

    public PublicKeyInfo get(String appKey) { return store.get(appKey); }
}
```

### 6.4 application.yml 改动

```yaml
server:
  port: 8470          # 原 8471，本次改回，与文档/README 对齐

# 新增：验签公钥服务（供网关 SPI 直连，不经网关）
sign:
  public-key-store:
    keys:
      biz001: biz-public-key.pem
```

---

## 7. docker-compose-ShenYu.yaml 改动

目标文件：`D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`
（stock 镜像 + ext-lib bind mount 方案不变，只改 `shenyu-bootstrap` 的 environment）

**旧（Redis）**：

```yaml
- GW_SIGN_KEY_SOURCE=redis
- GW_SIGN_REDIS_HOST=host.docker.internal
- GW_SIGN_REDIS_PORT=6379
- GW_SIGN_REDIS_DATABASE=0
- GW_SIGN_REDIS_PASSWORD=123456
- GW_SIGN_REDIS_TIMEOUT_MS=1500
- GW_SIGN_REDIS_BIZ_PUBLIC_KEY_PATTERN=shenyu:sign:%s:biz-public-key.pem
- GW_SIGN_CACHE_TTL_SECONDS=30
- GW_SIGN_CACHE_FAILURE_RETRY_SECONDS=3
- GW_SIGN_CACHE_ALLOW_STALE_ON_REFRESH_FAILURE=true
```

**新（HTTP）**：

```yaml
- GW_SIGN_KEY_SOURCE=http
- GW_SIGN_HTTP_BASE_URL=http://10.19.236.150:8470   # demo 实际 IP（宿主机网卡）
- GW_SIGN_HTTP_PATH_PATTERN=/sign/public-key/%s
- GW_SIGN_HTTP_CONNECT_TIMEOUT_MS=1000
- GW_SIGN_HTTP_READ_TIMEOUT_MS=2000
- GW_SIGN_HTTP_MAX_CONNECTIONS=20
- GW_SIGN_CACHE_TTL_SECONDS=30
- GW_SIGN_CACHE_FAILURE_RETRY_SECONDS=3
- GW_SIGN_CACHE_ALLOW_STALE_ON_REFRESH_FAILURE=true
```

> 注：`base-url` 用宿主机网卡 IP（`10.19.236.150`）而非 `host.docker.internal`，
> 与现有 docker-compose 的 `extra_hosts: host.docker.internal:host-gateway` 二选一，本项目沿用 IP 直连更稳。

---

## 8. 端到端验证步骤

```bash
# 1. 重新生成密钥对（放 demo）
openssl genrsa -out shenyu-springcloud-demo/src/main/resources/keys/biz-private-key.pem 2048
openssl rsa -in shenyu-springcloud-demo/src/main/resources/keys/biz-private-key.pem \
  -pubout -out shenyu-springcloud-demo/src/main/resources/keys/biz-public-key.pem
# 复制一份公钥到 SPI 项目作为兜底
cp shenyu-springcloud-demo/src/main/resources/keys/biz-public-key.pem \
   shenyu-sign-gateway-spi/src/main/resources/biz-public-key.pem

# 2. 编译 SPI
cd shenyu-sign-gateway-spi && mvn clean package -DskipTests

# 3. 部署 jar 到 ext-lib
cp target/shenyu-sign-gateway-spi-2.6.1.jar \
   D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1/shenyu-bootstrap/ext-lib/

# 4. 重启 bootstrap（让新 jar 生效）
docker restart shenyu-bootstrap-261

# 5. 更新 docker-compose-ShenYu.yaml 环境变量（gw.sign.redis.* → gw.sign.http.*）

# 6. 启动 demo（端口 8470）
cd shenyu-springcloud-demo && mvn spring-boot:run

# 7. 验证 demo 直连（SPI 模拟）
curl http://127.0.0.1:8470/sign/public-key/biz001

# 8. 完整验签链路（业务请求 → 网关 sign 插件 → SPI → demo）
#    用 sign-demo-biz 的 PaySignInterceptor 发请求，看是否验签通过
```

---

## 9. 不做的事（边界）

- 不动 demo 已有的 `OrderController` 5 个接口（业务侧不变）。
- 不动 demo 已有的 11 个单测（仅追加 sign 相关测试）。
- 不引入 OkHttp / Spring WebClient（保持 Apache HttpClient + 同步风格）。
- 不动 Nacos / springCloud 插件路径（SPI 不通过 Nacos discovery 找 demo，直接用配置的 base-url）。
- 不动现有 ShenYu admin 配置（selector / rule / meta_data 全不变）。
- 不删除 SPI 项目的 Dockerfile / build-image.sh（bind mount 是当前方案，但 Dockerfile 仍是可用备选）。

---

## 10. 风险与已知边界

1. **demo 重启期间网关验签全失败**：HTTP 模式下 demo 是单点（不像 Redis 是独立服务）。
   三级兜底能扛 demo 短暂挂掉（< TTL=30s），但长时间挂会最终降级到 classpath PEM（单一固定公钥），多租户能力丢失。
   生产建议 demo 做高可用（多实例 + 负载均衡），但 SPI 当前只支持单一 base-url（不做 loadbalance）。
2. **SPI 启动期不调 demo**：SPI 启动只创建 HttpClient，不主动 ping demo。第一次请求到来才触发 HTTP 调用。
   启动期 demo 不可达不会让 SPI 启动失败。
3. **classpath PEM 是全局兜底**：所有 appKey 在 HTTP + stale 都失败时共用同一个 classpath PEM（与现有 Redis 实现一致）。
4. **端口统一**：历史多实例（8470/8471 都监听），本次强制统一 8470，需确保旧 8471 进程停掉。

---

## 11. 关键文件路径索引

| 角色 | 路径 |
|------|------|
| SPI 主类（待删） | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/DynamicBizPublicKeyProvider.java` |
| SPI 接口（保留） | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/BizPublicKeyProvider.java` |
| SPI 配置（待改） | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/PayRsaSignConfiguration.java` |
| SPI 验签服务（不变） | `shenyu-sign-gateway-spi/src/main/java/org/apache/shenyu/plugin/sign/custom/PayRsaSignService.java` |
| SPI pom（待改） | `shenyu-sign-gateway-spi/pom.xml` |
| SPI 部署补丁 | `shenyu-sign-gateway-spi/docker-compose-shenyu-bootstrap-patch.yaml` |
| demo 配置（待改） | `shenyu-springcloud-demo/src/main/resources/application.yml` |
| demo 接口（待建） | `shenyu-springcloud-demo/src/main/java/.../sign/SignController.java` |
| 网关 compose | `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml` |

---

## 12. 实施顺序

1. 生成 RSA 密钥对 → demo `keys/` + SPI `resources/biz-public-key.pem`
2. demo：`SignProperties` / `PublicKeyInfo` / `PublicKeyStore` / `SignController` + `application.yml` 配置
3. demo：单测 `SignControllerTest` / `PublicKeyStoreTest` + `.http` 追加
4. SPI：删 `DynamicBizPublicKeyProvider` + `init-redis-public-key.sh`；新建 `HttpBizPublicKeyProvider`
5. SPI：`pom.xml` 依赖替换；`PayRsaSignConfiguration` 改读 `gw.sign.http.*`
6. SPI：单测 `HttpBizPublicKeyProviderTest`
7. compose：`docker-compose-ShenYu.yaml` 环境变量改写
8. 编译 → 部署 jar → 重启 bootstrap → 启动 demo → 端到端验证

---

_相关文档：docs/sign-redis-rotation-runbook.md、docs/sign-plugin-验证手册.md、docs/shenyu-springcloud-2.6.1-分析总结.md_



基于您提供的改造计划文本，我进行了深度的架构与逻辑分析。整体而言，这份通过 Grilling（逐步询问与决策）梳理出的计划**逻辑非常严密、边界清晰（特别是“不做的事”设定得很好）、容灾设计（三级兜底）极具生产意识**。

但在深度审查中，我发现了**一个致命的架构隐患**，并对您关心的“映射逻辑”和“对现有正常启动（qd）功能的影响”进行了专项评估。以下是详细的分析报告：

### 一、 核心风险预警（致命级）：WebFlux 环境下的同步阻塞

**问题定位**：决策 4 选择了 **Apache HttpClient（同步）**。
**深度分析**：ShenYu 网关底层是基于 Spring WebFlux (Netty) 的反应式架构。当请求进入 `SignPlugin` 时，执行线程极大概率是 Netty 的 EventLoop 线程（如 `reactor-http-nio-*`）。
如果在这种线程中执行**同步且阻塞**的 Apache HttpClient 网络调用，会导致 EventLoop 线程被挂起。这不仅会阻塞当前请求，还会**阻塞该线程上处理的所有其他并发请求**，导致网关吞吐量断崖式下跌，甚至在 demo 响应慢时引发网关大面积超时和死锁。

**修正建议（必须执行）**：
在 `HttpBizPublicKeyProvider` 中，绝对不能直接在当前线程调用 `httpClient.execute()`。必须将 HTTP 调用切换到弹性线程池：
```java
// 在 SPI 内部维护一个专用的线程池，或使用 Reactor 的弹性调度器
private final ExecutorService httpExecutor = Executors.newFixedThreadPool(10);

public PublicKey currentKey(String appKey) {
    // ... 缓存逻辑 ...
    // 将阻塞的 HTTP 调用放入独立线程，避免阻塞 Netty EventLoop
    Future<String> future = httpExecutor.submit(() -> fetchPemViaHttpClient(appKey));
    String pem = future.get(readTimeoutMs, TimeUnit.MILLISECONDS); 
    // ... 解析与兜底逻辑 ...
}
```

---

### 二、 Grilling 决策合理性深度剖析

#### 1. 架构与网络拓扑（决策 1、9）—— **极度合理**
*   **直连 demo IP 绕过网关**：这是整个改造的**点睛之笔**。如果 SPI 通过网关（9196端口）去调用 demo 获取公钥，请求会再次进入 `SignPlugin` 触发验签，形成**死循环**。直连 demo 的 8470 端口完美避开了循环依赖。
*   **ext-lib bind mount**：继续采用官方推荐的插件扩展方式，不重新构建基础镜像，在调试和迭代期是最高效、最合理的选择。

#### 2. 容灾与高可用（决策 5、6）—— **极佳的生产级设计**
*   **三级兜底（HTTP -> stale 旧缓存 -> classpath PEM）**：完全对齐甚至超越了原 Redis 方案的可靠性。特别是保留 `stale`（过期缓存）机制，能有效应对 demo 短暂重启或网络抖动，保证网关在密钥服务不可用时依然能“带病工作”。

#### 3. 接口与契约（决策 2、3、12）—— **标准且规范**
*   RESTful 路径变量 + JSON 包裹响应是标准做法。将端口统一修复为 8470 消除了文档与代码的割裂，非常必要。

---

### 三、 “映射”逻辑专项分析（针对 appKey -> PEM 映射）

您选择了 **决策 14（`@ConfigurationProperties` 加载 yml 映射）** 和 **决策 7（demo classpath 加载）**。

**合理性评估：正常且合理，但有优化空间。**
*   **优点**：零 DB 依赖，启动时一次性加载到内存 Map，读取速度极快（O(1)），完全符合公钥“低频变更、高频读取”的特性。
*   **潜在痛点**：如果未来需要新增或轮换 appKey，必须修改 yml 并**重启 demo 服务**。
*   **优化建议**：
    1.  **配置内容而非文件名**：建议在 yml 中直接配置 PEM 的**多行字符串内容**，而不是文件名。这样可以减少 classpath 下零散文件的管理成本。
        ```yaml
        sign:
          public-key-store:
            mappings:
              biz001: |
                -----BEGIN PUBLIC KEY-----
                MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...
                -----END PUBLIC KEY-----
        ```
    2.  **增加热刷新机制（可选）**：在 `SignController` 中增加一个内部接口 `POST /sign/refresh`（仅限内网调用），调用时重新从 yml 或配置中心拉取最新映射并替换内存 Map，实现免重启热更新。

---

### 四、 对现有正常功能的影响分析（针对“正常 qd/启动”）

您非常关心改造是否会破坏当前正常运行的部分（如 OrderController、Nacos 注册等）。**结论是：完全隔离，不会影响现有正常启动和运行。**

1.  **业务接口零侵入**：计划明确“不动 demo 已有的 OrderController 5 个接口”，业务侧完全无感。
2.  **Nacos 注册与网关路由完美隔离**：
    *   **事实依据**：ShenYu 的 `SpringCloudClientEventListener` 只会扫描并注册带有 `@ShenyuSpringCloudClient` 注解的 Bean。
    *   **设计巧妙之处**：新增的 `SignController` **只标 `@RestController`，不标 `@ShenyuSpringCloudClient`**。这意味着它**不会被注册到 Nacos**，网关的 springCloud 插件也**绝对不会**将外部流量路由到 `/sign/public-key/**`。它仅仅作为 demo 自身 8470 端口的一个“隐藏”内部服务存在，供 SPI 直连调用。这保证了现有网关路由规则的纯洁性。
3.  **单测与回归**：保留了原有的 11 个单测并追加新测试，保证了代码重构后的质量底线。

---

### 五、 Docker 与 Jar 包加载逻辑检查

**逻辑链条验证：正常且闭环。**
1.  **编译**：`mvn clean package` 生成 `shenyu-sign-gateway-spi-2.6.1.jar`。
2.  **部署**：将 jar 拷贝到宿主机的 `shenyu-bootstrap/ext-lib/` 目录。
3.  **加载**：`docker-compose-ShenYu.yaml` 中通过 bind mount 将宿主机的 `ext-lib` 映射到容器内的 `/opt/shenyu-bootstrap/ext-lib`。ShenYu 官方镜像的启动脚本会自动将该目录下的 jar 加入 classpath。
4.  **配置生效**：yaml 中配置的 `GW_SIGN_HTTP_BASE_URL` 等环境变量，通过 Spring Boot 的 relaxed binding 机制，自动映射到 `gw.sign.http.base-url` 配置项，被 `HttpBizPublicKeyProvider` 读取。

**唯一需要注意的细节**：
在 Docker 容器内，`127.0.0.1` 指的是容器自身。因此，`GW_SIGN_HTTP_BASE_URL` **绝对不能**配置为 `http://127.0.0.1:8470`，必须配置为**宿主机局域网 IP**（如 `http://10.19.236.150:8470`）或使用 `host.docker.internal`（如果 Docker 版本支持）。您的计划中已经注意到了这一点（写了实际 IP），非常严谨。

---

### 六、 总结与最终建议

这份改造计划在业务逻辑、架构隔离、容灾兜底方面设计得**非常出色且合理**。只需在实施阶段重点解决以下两个技术细节，即可完美落地：

1.  **必须解决 WebFlux 线程阻塞问题**：在 `HttpBizPublicKeyProvider` 中，务必将 Apache HttpClient 的同步调用封装到独立的 `ExecutorService` 线程池中执行，严禁在 Netty EventLoop 线程中直接发起同步 HTTP 请求。
2.  **优化 yml 映射配置**：建议直接在 yml 中配置 PEM 文本内容而非文件名，并考虑预留一个内存 Map 的热刷新接口，以提升后续运维效率。

您可以放心地基于此计划进入代码实施阶段。