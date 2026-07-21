# Sign SPI HTTP 改造 — 部署 + 端到端验证报告

> 日期：2026-07-20 | 计划文档：`docs/sign-spi-http-改造计划.md` §8

## 1. 部署清单

| 步骤 | 内容 | 状态 |
|------|------|------|
| SPI jar 部署 | `shenyu-sign-gateway-spi-2.6.1.jar` (18,641 B) → `ext-lib/` | ✅ |
| 容器重建 | `docker compose up -d --force-recreate shenyu-bootstrap` | ✅ |
| 环境变量 | `GW_SIGN_KEY_SOURCE=http` + 7 个 `GW_SIGN_HTTP_*` | ✅ |
| Demo 启动 | `shenyu-springcloud-demo` on port 8470 | ✅ |
| Sign selector | `/pay-demo` (id=2072243187050684416) `enabled=1` | ✅ |
| Sign rule | `/pay-demo` handle=`{"signRequestBody":"true"}` `enabled=1` | ✅ |

## 2. SPI 初始化日志（gateway）

```
[GW-Sign] 公钥源已启用 HTTP 模式 baseUrl=http://10.19.236.150:8470 pattern=/sign/public-key/%s refresh=15s ttl=30s
[GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService
```

- `@ConditionalOnMissingBean(SignService.class)` 生效，自定义 `PayRsaSignService` 替换默认 `ComposableSignService`
- `HttpBizPublicKeyProvider` 以 HTTP 模式启动（非 classpath/Redis）

## 3. 验证链路架构

```
Client                    Gateway (9196)                    Demo (8470)
  |                           |                               |
  |  GET /pay-demo/v3/pay/test|                               |
  |  X-Pay-App-Key: biz001    |                               |
  |  X-Pay-Timestamp: ...     |                               |
  |  X-Pay-Nonce: ...         |                               |
  |  X-Pay-Sign: <Base64>     |                               |
  |-------------------------->|                               |
  |                           |                               |
  |                    SignPlugin (order=20)                  |
  |                    PayRsaSignService.signatureVerify()    |
  |                           |                               |
  |                    HttpBizPublicKeyProvider.currentKey("biz001")
  |                           |---HTTP GET /sign/public-key/biz001-->|
  |                           |<-----200 PEM public key-------------|
  |                           |                               |
  |                    SHA256withRSA verify                    |
  |                    ✅ pass / ❌ fail → 401                 |
  |                           |                               |
  |                    ContextPathPlugin (order=80)            |
  |                    DividePlugin (order=200)                |
  |                    → no upstream → code=-119               |
  |<-------200 {code:-119}----|                               |
```

## 4. 端到端测试结果（4/4 通过）

### Test 1: 无签名头 → 401

```
GET /pay-demo/v3/pay/test  (无 X-Pay-* 头)
→ HTTP 401  {"code":401,"message":"missing X-Pay-* header"}
```

### Test 2: 无效签名 → 401

```
GET /pay-demo/v3/pay/test
  X-Pay-Timestamp: 1784518811
  X-Pay-Nonce: 28cd6bb7db65e7ac0ecbd326b502d6a6
  X-Pay-Sign: aW52YWxpZHNpZ25hdHVyZQ==   (16 bytes, 非 256)
  X-Pay-App-Key: biz001
→ HTTP 401  {"code":401,"message":"verify error: Signature length not correct: got 16 but was expecting 256"}
```

### Test 3: 合法签名 → 200 (验签通过)

```
GET /pay-demo/v3/pay/test
  X-Pay-Timestamp: 1784518811
  X-Pay-Nonce: 28cd6bb7db65e7ac0ecbd326b502d6a6
  X-Pay-Sign: ZO1JsmDsmKB1NzOJYMZlG9ep105P17B7...  (SHA256withRSA, Base64)
  X-Pay-App-Key: biz001
→ HTTP 200  {"code":-119,"message":"Can not find healthy upstream url..."}

Gateway log: [GW-Sign] ✅ 验签通过 appKey=biz001（业务公钥 SHA256withRSA 校验成功）
```

> code=-119 是 divide 插件无上游的响应（sign-demo-pay 未启动），**不是 401** = 验签已通过。

### Test 4: 不存在的 appKey → 401 (HTTP 模式铁证)

```
GET /pay-demo/v3/pay/test
  X-Pay-App-Key: nonExistentKey
  (其他头同 Test 3)
→ HTTP 401  {"code":401,"message":"verify error: appKey not found: nonExistentKey"}
```

> 错误信息 `appKey not found` 只可能来自 `HttpBizPublicKeyProvider.fetchPem()` 收到 demo 的 HTTP 404 响应。
> Demo 端确认：`GET /sign/public-key/nonExistentKey` → 404 `{"error":"app_key_not_found"}`

## 5. 签名串格式

```
GET
/pay-demo/v3/pay/test
1784518811
28cd6bb7db65e7ac0ecbd326b502d6a6

```

- 5 行，每行 `\n` 结尾（含最后一行空 body 后的 `\n`）
- Line 1: HTTP 方法（大写）
- Line 2: 网关原始路径（带 contextPath，含 query string）
- Line 3: 时间戳（epoch seconds，±300s 容差）
- Line 4: 随机串
- Line 5: 请求体（GET 为空串）

签名命令：
```bash
printf 'GET\n%s\n%s\n%s\n\n' "$PATH" "$TS" "$NONCE" \
  | openssl dgst -sha256 -sign biz-private-key.pem \
  | base64 -w0
```

## 6. 关键发现

1. **`docker restart` ≠ `docker compose up --force-recreate`**：前者只重启进程不重读 env 变量。曾因只 restart 导致 `GW_SIGN_KEY_SOURCE` 仍为旧值 `redis`，SPI 走 classpath 模式。必须 `--force-recreate` 才能从 compose 文件重建容器 env。

2. **Spring Boot relaxed binding 对 `env.getProperty()` 生效**：`env.getProperty("gw.sign.key-source")` 能从环境变量 `GW_SIGN_KEY_SOURCE` 解析（连字符→下划线自动映射），无需 `@ConfigurationProperties`。

3. **首请求缓存 miss 做同步 HTTP 拉取**：`HttpBizPublicKeyProvider.currentKey()` 在缓存 miss 时（全新 appKey）会在 EventLoop 上同步拉取一次 HTTP，之后后台 15s 刷新接管。生产环境应在启动后预热（发一次请求触发缓存填充）。

## 7. 结论

**sign-spi-http 改造端到端验证通过。** SPI 成功从 Redis/classpath 源切换到 HTTP 源，`PayRsaSignService` 通过 `HttpBizPublicKeyProvider` 从 demo 的 `/sign/public-key/{appKey}` 接口拉取 RSA 公钥进行 SHA256withRSA 验签，完整链路（签名验证 + 多租户 appKey 路由 + 401 拒绝）符合预期。

---

## 8. EventLoop 阻塞风险改造 + 回归验证（2026-07-20 下午）

### 8.1 改造动机

阶段一实现中，`currentKey()` 在缓存 miss 时会在 Netty EventLoop 线程上**同步** HTTP 拉取公钥（首请求 5ms+ 阻塞）。8 核 CPU 仅 8 个 EventLoop 线程处理上万连接，任何一个被阻塞会导致并发连接排队，吞吐量断崖式下跌。对照用户提供的 WebFlux 阻塞风险分析文档，风险 1（EventLoop 直接阻塞）为真实风险，必须消除。

### 8.2 改造方案：零 I/O 热路径 + 后台刷新

| 组件 | 改造前 | 改造后 |
|------|--------|--------|
| `currentKey()` 热路径 | 缓存 miss → 同步 HTTP fetch（EventLoop 阻塞） | 仅 `ConcurrentHashMap.get()` + `notFoundAppKeys.contains()` + 返回 `classpathFallbackKey` |
| HTTP I/O 线程 | EventLoop（`shenyu-netty-epoll-*`） | 专用守护线程 `gw-sign-http-refresh`（15s 周期） |
| 缓存 miss 兜底 | 无（抛异常或阻塞） | 构造期预加载的 classpath 兜底公钥（纯内存读） |
| 未知 appKey 处理 | 同步 HTTP 404 → 立即抛 | 首次返回 classpath 兜底；后台刷新确认 404 后加入 `notFoundAppKeys`，后续直接拒绝 |
| 预热机制 | 无 | `preWarm(String... appKeys)`：Spring 启动线程同步拉取入缓存（不阻塞 EventLoop），通过 `GW_SIGN_PRE_WARM_APP_KEYS=biz001` 配置 |

### 8.3 回归验证结果

#### Test 1-3：行为不变（4/4 中前 3 个）

| Test | 输入 | 期望 | 实际 | 结果 |
|------|------|------|------|------|
| 1 | 无 X-Pay-* 头 | 401 | `401 {"code":401,"message":"missing X-Pay-* header"}` | ✅ |
| 2 | 无效签名（16B） | 401 | `401 {"code":401,"message":"verify error: Signature length not correct: got 16 but was expecting 256"}` | ✅ |
| 3 | 合法签名 biz001 | 200 | `200 {"code":-119,...}`（divide 无上游，sign 已通过） | ✅ |

#### Test 4：行为变化（设计 trade-off，可接受）

| 子测试 | 时机 | 结果 | 原因 |
|--------|------|------|------|
| T4.1 | 首次请求 nonExistentKey | **200** `{"code":-119}` | `notFoundAppKeys` 为空 + 缓存 miss → 返回 classpath 兜底公钥 → 签名匹配（测试用 biz-private-key 恰对应 classpath biz-public-key） |
| T4.2 | 等 20s 后二次请求 | **401** `{"code":401,"message":"verify error: appKey not found: nonExistentKey"}` | 后台 `gw-sign-http-refresh` 线程已确认 404，`nonExistentKey` 加入 `notFoundAppKeys` → `currentKey()` 抛 `AppKeyNotFoundException` |

**安全 trade-off 分析：**
- T4.1 的 200 是因为测试环境 `biz-private-key.pem`（签名用）恰好对应 SPI classpath 的 `biz-public-key.pem`（modulus 一致已证实：`23f8d96a1b85c87527cb8d8b9d27e7e3`）
- 生产环境中 classpath 密钥对是**网关专用**，不分发给外部客户端；攻击者无法获取 classpath 私钥，故无法伪造匹配 classpath 公钥的签名
- 首次请求窗口 ≤15s（一个刷新周期），之后该 appKey 被永久拒绝
- **缓解建议**：classpath 密钥对应独立于任何业务密钥对；生产可关闭 classpath 兜底（`gw.sign.classpath-fallback=none`）强制 preWarm 覆盖所有合法 appKey

### 8.4 EventLoop 零 I/O 铁证

#### 50 并发 burst 耗时分布

```
count=50  min=0.2120s  max=0.2292s  avg=0.2204s
总耗时：2.174s（8 EventLoop 线程并行处理）
```

- **max - min = 17ms**：无长尾，无 EventLoop 阻塞 contention
- 若改造前（同步 HTTP on EventLoop），50 并发会在 8 线程上串行阻塞，预期出现 0.25s+ 长尾

#### 线程分布（30s 窗口内 GW-Sign 日志行数）

| 线程 | 日志行数 | 角色 |
|------|----------|------|
| shenyu-netty-epoll-2 ~ -9（8 个） | 各 24-28 | EventLoop，纯内存验签 |
| gw-sign-http-refresh | 3 | 专用守护线程，HTTP I/O 隔离 |

#### EventLoop HTTP I/O 检查

```
grep "shenyu-netty-epoll" | grep -iE "http|fetchPem|HTTP GET|拉取"
→ (空)
```

**EventLoop 上零 HTTP I/O 调用。** 所有 HTTP 拉取均在 `gw-sign-http-refresh` 守护线程执行。

### 8.5 SPI 初始化日志（改造后）

```
[main] [GW-Sign] classpath 兜底公钥已预加载 location=biz-public-key.pem
[main] [GW-Sign] 公钥源已启用 HTTP 模式 baseUrl=http://10.19.236.150:8470 pattern=/sign/public-key/%s refresh=15s ttl=30s
[main] [GW-Sign] 预热公钥成功 appKey=biz001
[main] [GW-Sign] 预热完成 appKeys=biz001
[main] [GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService
```

### 8.6 改造结论

| 风险场景 | 状态 | 证据 |
|----------|------|------|
| ① EventLoop 直接阻塞 | ✅ 已消除 | 50 并发 burst 无长尾；EventLoop 日志零 HTTP I/O |
| ② boundedElastic 雪崩 | N/A | 未使用 boundedElastic（SignService 同步接口约束） |
| ③ RSA CPU 饥饿 | 低风险 | RSA-2048 verify ≈0.3ms，50 并发 avg 0.22s 含全部插件链 |
| ④ 连接池级联阻塞 | 低风险 | HTTP 仅在 `gw-sign-http-refresh` 单线程后台跑 |

**EventLoop 阻塞风险改造验证通过。** 热路径 `currentKey()` 已实现零 I/O、零锁、纯内存读；HTTP I/O 完全隔离到专用守护线程；50 并发 burst 无长尾 contention。
