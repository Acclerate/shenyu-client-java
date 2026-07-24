# shenyu-admin-appauth-spi 说明

> 模块：`shenyu-admin-appauth-spi`
> 版本：`2.6.1`
> 位置：`D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi`
>
> 一句话：在 shenyu-admin 进程内通过 ext-lib 加载的扩展 jar，新增一个 REST 接口
> `POST /appAuth/customCreate`，支持用**指定 appKey** 创建/更新 `app_auth` 记录，
> 并触发 WebSocket 推送，让网关 `SignAuthDataCache` 秒级拿到公钥。

---

## 1. 解决什么问题

ShenYu 2.6.1 admin 原生 REST 接口**无法用指定 appKey 创建 `app_auth` 记录**：

| 原生接口 | 问题 |
|---|---|
| `POST /appAuth/apply` | `AppAuthDO.create(AuthApplyDTO)` 硬编码 `appKey = SignUtils.generateKey()`，随机覆盖入参 appKey；且响应 `data = null`，不回传生成的 appKey |
| `POST /appAuth/updateDetail` | 要求 `id` 已存在（`@Existed`）；且其 update SQL 的 SET 子句**不含 app_key**，无法改 appKey |
| `POST /appAuth/createOrUpdate` | 未暴露为 REST 端点；且 id 为空时 `setAppSecret(generateKey())` 随机覆盖 appSecret |
| `GET /appAuth/updateSk` | 只改 appSecret，**不发布 DataChangedEvent**，网关收不到推送 |

业务方（erpm-pay-center）的 appKey 来自 `pay_app_config.app_key`，是写死在业务方签名代码里的稳定契约标识，不能被 admin 随机生成。因此需要一个能"用指定 appKey 落库 + 触发推送"的接口。本扩展 jar 就是补这个能力。

---

## 2. 落地实现

### 2.1 工程结构

```
shenyu-admin-appauth-spi/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/apache/shenyu/admin/custom/
    │   │   ├── AppAuthCustomConfiguration.java      装配入口（@Configuration）
    │   │   ├── AppAuthCustomCreateController.java   REST 端点
    │   │   ├── AppAuthCustomCreateService.java      核心逻辑（upsert + 推送）
    │   │   └── dto/
    │   │       ├── CustomAppAuthCreateReq.java      请求体
    │   │       └── CustomAppAuthCreateResp.java     响应体
    │   └── resources/META-INF/spring.factories
    └── test/java/.../AppAuthCustomCreateServiceTest.java   4 个单测
```

包名 `org.apache.shenyu.admin.custom`（加 `.custom` 后缀，不冒充官方 `mapper/service/controller` 包，避免 split package）。

### 2.2 装配机制

- `META-INF/spring.factories` 注册 `AppAuthCustomConfiguration` 为 `EnableAutoConfiguration`。
- admin 是标准 Spring Boot 应用，ext-lib jar 被 `entrypoint.sh` 拼进 JVM classpath（`-classpath .../ext-lib/*`），Spring Boot 启动扫到 spring.factories 即装配。
- 所有 Bean 用 `@Bean` 在 `@Configuration` 显式声明，不用 `@Component`（与 shenyu-sign-gateway-spi 一致）。

### 2.3 核心逻辑：`AppAuthCustomCreateService#upsertAndPush`

```
入参 req { appKey, appSecret, enabled, open }
  │
  ├─ appAuthMapper.findByAppKey(appKey)  查是否已存在
  │
  ├─ 不存在 → insertSelective 创建
  │     • id = UUIDUtils.generateShortUuid()
  │     • appKey = 入参（不被随机覆盖）
  │     • appSecret = 入参 PEM
  │     • userId = appKey（必填列，网关 AppAuthData 不下发 userId，语义无害）
  │     • enabled/open = 入参（默认 true/false）
  │     • eventType = CREATE
  │
  ├─ 已存在 → updateSelective 更新
  │     • 复用已存在记录的 id（appKey 不可变，updateSelective 的 SET 不含 app_key）
  │     • appSecret/enabled/open 用入参覆盖
  │     • eventType = UPDATE
  │
  └─ eventPublisher.publishEvent(DataChangedEvent(APP_AUTH, eventType, [AppAuthData]))
        → 触发 WebsocketDataChangedListener 推送所有 bootstrap 的 SignAuthDataCache（秒级生效）
```

**关键设计点**：

1. **不复用 `AppAuthService.createOrUpdate`**：它的 id 为空分支会 `setAppSecret(generateKey())` 随机覆盖 appSecret。本服务自控全字段规避该覆盖。
2. **不裸调 Mapper 不发事件**：裸 insert/update 只落库，网关 SignAuthDataCache 收不到推送。必须手动 `publishEvent`，与原生 `updateDetail` 推送语义一致。
3. **updateSelective 而非 update**：`updateSelective` 的 SET 子句含 appSecret/enabled/open、不含 app_key（appKey 不可变，符合需求）；`update` 是全字段覆盖会清空 phone/userId 等，不适用。

### 2.4 REST 接口契约

```
POST /appAuth/customCreate
Header: X-Access-Token: <admin 登录 token>     # Shiro statelessAuth 全局拦截，必须带
Content-Type: application/json

Body:
{
  "appKey":   "<指定 appKey，必填，对应 pay_app_config.app_key>",
  "appSecret":"<RSA 公钥 PEM，必填，对应 pay_app_config.app_public_key>",
  "enabled":  true,      # 可空，默认 true
  "open":     false      # 可空，默认 false
}

响应:
{
  "code": 200,
  "message": null,
  "data": {
    "id":     "<app_auth 记录 id>",
    "appKey": "<回传 appKey>"
  }
}
```

**字段映射**（与设计方案 §2.3 一致）：

| app_auth 列 | 来源 | 说明 |
|---|---|---|
| `id` | 扩展生成 / 已存在记录复用 | — |
| `app_key` | 请求体 `appKey` | 指定值，不被随机覆盖（核心能力） |
| `app_secret` | 请求体 `appSecret` | RSA 公钥 PEM，语义重载 |
| `enabled` | 请求体，默认 true | 验签需要启用 |
| `open` | 请求体，默认 false | 不开路径白名单 |
| `user_id` | 复用 appKey 值 | DB 必填列；网关 AppAuthData 不下发此字段 |

### 2.5 鉴权策略

- **token 校验**：`/appAuth/customCreate` 被 admin 的 `/**` → `statelessAuth` 自动覆盖，调用方必须带 `X-Access-Token`。无需配白名单。
- **不加 `@RequiresPermissions`**：刻意区别于原生 `updateDetail`（它要求 `system:authen:edit`）。原因：paycenter 用专设 pusher 服务账号，token 校验已挡住未授权访问，加权限注解反而要求运维额外给 pusher 账号授 `system:authen:edit`，增加配置负担。
- **paycenter 侧零成本**：`ShenyuAdminClient` 已有 token 缓存 + 401 自动重试，调本接口与原来调 `/plugin` 走相同 token 复用路径，无多次登录。

---

## 3. 依赖最小化

jar 产物仅 5 个自定义类（约 11KB），**零运行时外部依赖**。所有 admin/spring 依赖为 `provided`，运行时由 admin 进程 classpath 提供。

编译期通过 `<exclusions>` 切断所有 RPC/注册中心传递依赖，依赖树中**不含** grpc/netty/etcd/nacos/zookeeper/reactor：

- `shenyu-admin`（provided）：排除 `shenyu-discovery-{etcd,eureka,nacos,zookeeper}`、`shenyu-register-common`、`shenyu-admin-listener-{etcd,nacos,zookeeper,consul,polaris}`
- `shenyu-common`（provided）：排除 `reactor-netty-core`、`reactor-netty-http`

实际引用的 admin 类仅 4 个：`AppAuthMapper`、`AppAuthDO`、`ShenyuAdminResult`、`DataChangedEvent`；common 类 4 个：`AppAuthData`、`ConfigGroupEnum`、`DataEventTypeEnum`、`UUIDUtils`。

---

## 4. 构建与测试

```bash
cd D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi
mvn clean package
# 产物：target/shenyu-admin-appauth-spi-2.6.1.jar
```

单测 4 个，全绿：

| 用例 | 覆盖 |
|---|---|
| 创建分支 | findByAppKey=null → insertSelective + publishEvent(CREATE) + 响应回传 id |
| 更新分支 | findByAppKey=已存在 → updateSelective + publishEvent(UPDATE) + 复用已存在 id |
| 默认值 | enabled/open 为 null → 推送数据 enabled=true, open=false |
| disabled | enabled=false → 推送数据 enabled=false |

> **首次构建前置**：`shenyu-admin:2.6.1` 不在中央仓库，需先从 ShenYu 源码 `mvn install` 到本地仓库（见部署文档「构建前置」）。
