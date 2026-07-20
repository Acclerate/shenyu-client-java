# ShenYu 2.6.1 springCloud 插件 + Nacos 2.5.3 验证手册

> 目的：在 Docker 本地环境跑通「Spring Cloud 微服务 + Nacos 2.5.3 + ShenYu 2.6.1 springCloud 插件」全链路。
> 与既有 divide 路径的本质差异：springCloud 插件是**委托器**，selector 只存 `serviceId`，每次请求经
> `NacosDiscoveryClient` 现查 Nacos 实例列表；ShenYu **不入库、不探活**，实例生命周期完全由 Nacos 管。

## a. 环境

| 组件 | 地址 | 版本 | 备注 |
|------|------|------|------|
| mysql (Docker) | `mysql57:3306` / 宿主 `localhost:3306` | 5.7 | root/root |
| nacos (Docker) | `nacosserver253:8848` / 宿主 `localhost:8848` | **2.5.3**（2.5.x 末版，2026-07-14 发布） | gRPC `9848`；鉴权关闭 |
| shenyu-admin (Docker) | `shenyu-admin-261:9095` / 宿主 `localhost:9096` | 2.6.1 | admin/**1qaz!QAZ**（非默认 123456） |
| shenyu-bootstrap (Docker) | `shenyu-bootstrap-261:9195` / 宿主 `localhost:9196` | 2.6.1 | patch 后接入 `nacos_net_232` |
| demo (IDEA / mvn) | `localhost:8470` | Spring Boot 2.7.18 | serviceId = `shenyu-springcloud-demo` |
| demo contextPath | `/springcloud-demo` | — | 网关路径前缀 |

> 8848 / 9848 / 9849 端口与历史 nacos 容器互斥，启动 nacos 2.5.3 前必须先停历史 nacos。

## b. 启动顺序（严格有序）

### b.1 停掉当前运行的 nacos（8848 端口互斥）

```shell
# 在 gitee/docker-compose 仓库或直接 docker stop
docker stop nacosserver251 nacosserver253 nacosserver223 nacosserver231 nacosserver232 nacosserver204 2>/dev/null
# 验证 8848 已空闲
netstat -ano | findstr :8848     # Windows
# 或 ss -lntp | grep 8848        # Linux
```

### b.2 启动 nacos 2.5.3

```shell
cd D:/privategit/github/shenyu-client-java/docker/shenyu-springcloud-demo/nacos-2.5.3
docker-compose -f docker-compose-nacos-2.5.3.yml -p nacos253 up -d

# 验证 nacos 健康（期望 {"status":"UP"}）
curl http://127.0.0.1:8848/nacos/v1/console/health/readiness
```

### b.3 初始化 `nacos_config_253` 库（仅首次）

```shell
# (1) 建库
docker exec mysql57 mysql -uroot -proot \
  -e "CREATE DATABASE IF NOT EXISTS nacos_config_253 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

# (2) 从 2.5.3 镜像 dump schema 并导入（不写业务 SQL，仅建表脚本）
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint cat nacos/nacos-server:2.5.3 \
  /home/nacos/conf/mysql-schema.sql | docker exec -i mysql57 mysql -uroot -proot nacos_config_253

# (3) 重启 nacos 让它读到新库
docker-compose -f docker-compose-nacos-2.5.3.yml -p nacos253 restart

# (4) 验证 nacos 控制台可访问
# 浏览器打开 http://127.0.0.1:8848/nacos  (默认账号 nacos/nacos)
```

> **schema 来源说明**：nacos 2.5.3 镜像内的 `/home/nacos/conf/mysql-schema.sql` 是官方建表脚本，
> 本仓库不维护 SQL 文件，避免版本漂移；每次升级 nacos 镜像时直接 dump 即可。

### b.4 应用 bootstrap compose patch（让 bootstrap 接入 nacos 网络 + 开启 discovery）

详见 [`docker/shenyu-springcloud-demo/compose-patches/README.md`](../docker/shenyu-springcloud-demo/compose-patches/README.md)。

简要步骤：
1. 手动编辑 `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`，
   按 [`shenyu-261-bootstrap-attach-nacos-net.patch.yaml`](../docker/shenyu-springcloud-demo/compose-patches/shenyu-261-bootstrap-attach-nacos-net.patch.yaml) 改 3 处：
   - `services.shenyu-bootstrap.networks` 加 `- nacos_net`
   - `services.shenyu-bootstrap.environment` 加 `SPRING_CLOUD_*` / `SHENYU_SPRINGCLOUDCACHE_*` 项
   - 顶层 `networks` 加 `nacos_net`（external, name: `nacos_net_232`）
2. 验证：`docker-compose -f docker-compose-ShenYu.yaml config | grep nacosserver253`
3. 重启 bootstrap：
   ```shell
   cd D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1
   docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d shenyu-bootstrap
   ```
4. 连通性验证：
   ```shell
   docker exec shenyu-bootstrap-261 sh -c "wget -q -O - http://nacosserver253:8848/nacos/v1/ns/operator/metrics"
   # 期望返回 JSON 含 "status":"UP"
   ```

### b.5 admin 控制台开启 springCloud 插件

1. 浏览器打开 `http://127.0.0.1:9096`，登录（admin / 123456）。
2. 进入「基础配置 → 插件管理」。
3. 找到 **springCloud** 插件（PluginList → Proxy 下），点击「编辑」，把 **启用** 设为开。
   - 2.6.1 springCloud 插件无需 selector 级的 discovery 配置（与 2.7.0 的 discovery-mode 不同）。
4. 保存。

### b.6 启动 shenyu-springcloud-demo

```shell
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
mvn spring-boot:run
# 或 IDEA 直接运行 ShenYuSpringCloudDemoApplication.java
```

启动后查看日志，应看到三段关键证据（缺一不可）：

1. **Nacos 实例注册成功**：`c.a.c.n.registry.NacosServiceRegistry - nacos registry, DEFAULT_GROUP shenyu-springcloud-demo <IP>:8470 register finished`
2. **admin 登录成功**：`o.a.s.r.client.http.utils.RegisterUtils - login success: {...token...}`（若报 `accessToken is null` → admin 密码不对，见 g.7）
3. **metadata 推送成功**：3 条 `o.a.s.r.client.http.utils.RegisterUtils - metadata client register success: {...path...}`（findById/hello/echo-body 各一条）

> demo 自动注册会**同时在 admin 创建 springCloud selector + 3 条 rule + 3 条 meta_data**，
> 因此步骤 c 通常是可选的——除非要改条件为 ant 通配。
>
> Nacos 日志中 `User nacos not found` 的 ERROR 可忽略（见 g.4），不影响实际服务发现。

## c. admin 控制台配置（不写 SQL）

> **重要实操发现**：当业务服务启动时，`shenyu-spring-boot-starter-client-springcloud` 会**自动通过 HTTP 把 selector + rule + meta_data 推到 admin**，
> 覆盖大部分手工配置。**通常无需手工创建 selector / rule**，只要：
> 1. springCloud 插件已 enabled；
> 2. demo 配的 `shenyu.register` 账号密码正确（见 d.5 排查）；
> 3. `spring.application.name` 与 `contextPath` 配好。
>
> demo 自动注册产生的 selector：
> - name=`/springcloud-demo`（取自 contextPath）
> - condition=`uri startsWith /springcloud-demo/`
> - handle=`{"serviceId":"shenyu-springcloud-demo","gray":false}`
>
> demo 自动注册产生的 rule（每个 `@ShenyuRequestMapping` 方法一条）：
> - condition=`uri = /springcloud-demo/order/findById`（精确匹配）
> - handle=`{"timeout":3000,"loadBalance":"roundRobin"}`
>
> 如需手工创建（例如改条件为 ant 通配），参考下方模板。**注意 operator 必须用 `startsWith` / `pathPattern` / `=`，不要用 `pathMatch`** —— ShenYu 2.6.1 不存在 `pathMatch` 这个 SPI，会抛 `IllegalArgumentException: pathMatch name is error`。

### c.1 新建选择器（Selector）—— 可选，demo 启动会自动建

进入「PluginList → Proxy → springCloud → SelectorList → Add」：

| 字段 | 值 |
|---|---|
| Name（选择器名） | `springcloud-demo-selector` |
| Type（匹配类型） | `match`（自定义匹配） |
| Match Mode | `and` |
| Condition | 字段 `uri`，操作符 **`startsWith`**（不要用 pathMatch），值 `/springcloud-demo/` |
| Handle（JSON） | `{"serviceId":"shenyu-springcloud-demo","gray":false}` |

> `serviceId` **必须严格等于** `spring.application.name`（=`shenyu-springcloud-demo`）和 Nacos 上的服务名。
> 三者不一致 → springCloud 插件 `getInstances(serviceId)` 拿不到实例。

### c.2 新建规则（Rule）—— 可选，demo 启动会自动建

在选择器下「Add Rule」：

| 字段 | 值 |
|---|---|
| Name（规则名） | `springcloud-demo-order-rule` |
| Match Mode | `and` |
| Condition | 字段 `uri`，操作符 **`pathPattern`** 或 **`=`**，值 `/springcloud-demo/order/**` 或 `/springcloud-demo/order/findById` |
| Handle（JSON） | `{"path":"/springcloud-demo/order/**","timeout":3000,"loadbalance":"roundRobin"}` |

> `loadbalance` 取值：`roundRobin`（默认）/ `random` / `hash`。ShenYu 2.5.0+ 用自有 `shenyu-loadbalancer`，
> 不依赖 Ribbon。

### c.3 配置示意（Handle JSON）

selector handle（demo 自动注册的版本）：
```json
{"serviceId":"shenyu-springcloud-demo","gray":false}
```

rule handle（demo 自动注册的版本）：
```json
{"timeout":3000,"loadBalance":"roundRobin"}
```

## d. 验证命令

### d.1 demo 直连（不经网关，确认服务自身可用）

```shell
curl -s http://127.0.0.1:8470/order/findById?id=42 | jq .
# 期望返回 JSON，含 source=shenyu-springcloud-demo
```

### d.2 经网关访问（springCloud 插件 + Nacos discovery）

```shell
curl -i http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
# 期望 HTTP/1.1 200 + JSON 体（字段顺序无关）：
# {"instanceId":"<8位>","name":"springcloud-demo-order-42","id":"42",
#  "source":"shenyu-springcloud-demo","serverPort":"8470","timestamp":<毫秒>}
```

> 实测响应字段顺序（来自 Jackson 默认 HashMap 序列化）：
> `instanceId, name, id, source, serverPort, timestamp` —— 与 d.2 早期注释里的顺序可能不一致，**以实测为准**。

### d.3 Nacos 控制台验证实例已注册

浏览器打开 `http://127.0.0.1:8848/nacos`（nacos/nacos）→「服务管理 → 服务列表」：
- 应看到服务名 `shenyu-springcloud-demo`，分组 `DEFAULT_GROUP`
- 点进详情应看到 1 个实例，IP = 宿主机 IP（如 `10.19.236.150`），端口 `8470`，健康 = true

### d.4 bootstrap 日志验证（springCloud 委托器路径生效）

```shell
docker logs --tail 200 shenyu-bootstrap-261 | grep -iE "nacos|discovery|springcloud|ShenyuSpringCloudPlugin"
# 期望看到 NacosDiscoveryClient 周期性拉取实例、SpringCloudPlugin 命中路由的记录
```

## e. SQL 查询（仅查询，不写）

> 用户允许查询，不允许硬写 SQL。以下 SELECT 用于验证 admin 端数据闭环，不修改任何数据。

### e.1 验证 selector 已写入

```sql
SELECT id, name, plugin_id, match_mode, match_restful, enabled
FROM shenyu_261.selector
WHERE name = 'springcloud-demo-selector';
```

期望 1 行，`enabled=1`，`plugin_id` 指向 springCloud 插件。

### e.2 验证 selector handle 内容

```sql
SELECT id, name, handle
FROM shenyu_261.selector
WHERE name = 'springcloud-demo-selector';
-- handle 字段应为 {"serviceId":"shenyu-springcloud-demo","gray":false}
```

### e.3 验证 rule 已写入

```sql
SELECT id, name, selector_id, match_mode, handle
FROM shenyu_261.rule
WHERE name LIKE 'springcloud-demo%';
```

### e.4 验证 demo 的 @ShenyuSpringCloudClient 元数据已 HTTP 注册到 admin

```sql
SELECT id, app_name, path, service_name, rpc_type, enabled
FROM shenyu_261.meta_data
WHERE app_name = 'shenyu-springcloud-demo';
```

期望看到 `/springcloud-demo/order/findById`、`/springcloud-demo/order/hello`、`/springcloud-demo/order/echo-body` 三行，
`rpc_type=springCloud`，`enabled=1`。

> 执行方式：
> ```shell
> docker exec -it mysql57 mysql -uroot -proot shenyu_261 -e "<上面的 SQL>"
> ```

## f. 故障切换验证（证明走 springCloud 委托器路径）

这一步是**与 divide 路径的关键区别**：springCloud 插件每次请求现拉 Nacos 实例，实例下线后 ShenYu 即时感知。

```shell
# 1) 记录当前可访问
curl -s http://127.0.0.1:9196/springcloud-demo/order/findById?id=1 | jq .instanceId

# 2) 在 Nacos 控制台「服务管理 → 服务列表 → shenyu-springcloud-demo → 详情」
#    把唯一的实例下线（点「下线」按钮），或直接停掉 shenyu-springcloud-demo 进程

# 3) 等待 ≤ 10 秒（Nacos 心跳周期），再访问
curl -i http://127.0.0.1:9196/springcloud-demo/order/findById?id=2
# 期望：HTTP 503 或 500（springCloud 插件拿不到实例），不会缓存旧实例继续转发

# 4) 重新启动 demo，Nacos 控制台实例恢复 healthy 后再访问
curl -s http://127.0.0.1:9196/springcloud-demo/order/findById?id=3
# 期望：200，instanceId 与步骤 1 相同（同一 JVM）
```

> 对照实验：在 divide 路径下，实例下线后 ShenYu 自身的 `UpstreamCheckManager` 仍按配置探活周期
> （`shenyu.upstreamCheck.enabled`，默认关闭）决定何时摘除，且默认依赖被动失败而非主动发现。
> springCloud 路径把这件事完全委托给 Nacos，更贴合 Spring Cloud 微服务架构习惯。

## g. 常见问题

### g.1 `getInstances(serviceId)` 返回空（HTTP 200 + `code:-109 SpringCloud serviceId does not exist`）

- 检查 Nacos 控制台是否能看到 `shenyu-springcloud-demo` 服务、实例是否健康。
- 检查 demo 的 `spring.application.name` 是否与 selector handle 的 `serviceId` **完全一致**（区分大小写）。
- 检查 bootstrap 是否真的接入了 `nacos_net_232` 网络：`docker network inspect nacos_net_232 | grep shenyu-bootstrap`。

### g.2 `divide:Can not find selector`（HTTP 200 + `code:-107`）

**根因**：springCloud 插件没匹配到 selector，divide 插件兜底返回 -107。
最常见的原因是 **demo 的 metadata 没成功推到 admin**，因此 springCloud selector 没自动创建：

- 看 demo 启动日志，搜 `metadata client register success`，应看到 3 条（findById/hello/echo-body）。
- 如果是 `accessToken is null`：admin 密码不是默认 123456（本环境为 `1qaz!QAZ`），把 demo 的
  `application.yml` 里 `shenyu.register.props.password` 改成正确值，重启 demo。
- 如果是 `metadata client register success` 但 admin 仍没数据：检查 admin 是否 healthy、bootstrap 与 admin
  的 websocket 同步是否建立（`docker logs shenyu-bootstrap-261 | grep websocket`）。

### g.3 `pathMatch name is error`（HTTP 400 + `code:400`）

**根因**：某个 selector 或 rule 的 condition 用了 `pathMatch` 这个不存在的 operator。
ShenYu 2.6.1 支持的 operator：`startsWith` / `endsWith` / `=` / `match` / `pathPattern` / `regex` / `exclude` / `Groovy` / `contains` / `SpEL` / `TimeBefore` / `TimeAfter` / `path`。

排查 SQL：
```sql
-- selector
SELECT * FROM shenyu_261.selector_condition WHERE operator='pathMatch';
-- rule
SELECT * FROM shenyu_261.rule_condition WHERE operator='pathMatch';
```
DELETE 残留行，然后**重启 bootstrap**（清理内存缓存，admin 推送的 DELETE 事件不一定能让 bootstrap 立即同步）：
```shell
docker exec mysql57 mysql -uroot -proot -e "USE shenyu_261; DELETE FROM rule_condition WHERE operator='pathMatch'; DELETE FROM selector_condition WHERE operator='pathMatch';"
docker restart shenyu-bootstrap-261
```

### g.4 `User nacos not found` 反复出现在 demo / bootstrap 日志

**根因**：Nacos 2.5.x 即使 `NACOS_AUTH_ENABLE=false`，server 仍会对未定义用户名报 "User xxx not found"。
client 的 `SecurityProxy` 每次启动都尝试 login。

修法：**鉴权关闭时不传 username/password**（demo 与 bootstrap 侧都注释掉），让 client 走匿名（不调 login 接口）。
bootstrap 侧在 compose env 里去掉 `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME` / `PASSWORD` 即可。

### g.5 admin 控制台找不到 springCloud 插件

- 2.6.1 默认插件列表里就有 springCloud（plugin 表初始化数据），不需要额外导入。
- 若确实缺失：`SELECT id, name, role FROM shenyu_261.plugin WHERE name='springCloud';`
- 若 plugin 行存在但 `enabled=0`：在控制台「插件管理」开启即可，或通过 admin REST API：
  ```shell
  TOKEN=$(curl -s -G 'http://127.0.0.1:9096/platform/login' --data-urlencode 'userName=admin' --data-urlencode 'password=1qaz!QAZ' | jq -r '.data.token')
  # 此处 PUT 字段较多，建议直接 admin 控制台勾选「启用」更稳
  ```

### g.6 网关 404 / -107 / -109 都没有，但响应错误

- 用 `-i` 看完整响应：`curl -i http://127.0.0.1:9196/springcloud-demo/order/findById?id=42`
- 看 bootstrap 错误日志：`docker logs --since 10s shenyu-bootstrap-261 | grep -A 5 ERROR`
- 确认 path 拼装：contextPath + 类级 path + 方法级 path，三段拼接后才是 gateway 入站 path。
  `contextPath=/springcloud-demo` + `/order` + `/findById` = `/springcloud-demo/order/findById`。

### g.7 demo 控制台日志显示 HTTP 注册到 admin 失败

- 检查 demo 的 `shenyu.register.serverLists` 是否指向 admin（`http://localhost:9096`）。
- 检查 admin 账号密码（本环境为 `admin/1qaz!QAZ`，与默认的 `admin/123456` 不同）。
- admin 日志：`docker logs shenyu-admin-261 | grep -i "register\|metadata"`。
- **关键日志**：`o.a.s.r.client.http.utils.RegisterUtils - metadata client register success` —— 看到这条说明 metadata 推送成功。

## h. 与 divide 路径的本质差异（理论速查）

| 维度 | divide（既有 http-demo） | springCloud（本 demo） |
|---|---|---|
| 客户端注解 | `@ShenyuSpringMvcClient` | `@ShenyuSpringCloudClient`（或组合注解 `@ShenyuRequestMapping` 等） |
| upstream 来源 | 客户端 HTTP 注册推 admin，admin 推 bootstrap，bootstrap `UpstreamCacheManager` 内存+DB | NacosNamingService 维护，bootstrap 每次 `getInstances(serviceId)` 现拉 |
| 探活 | ShenYu 自身 `UpstreamCheckManager`（可配） | Nacos 心跳（实例自身 push） |
| 实例下线感知 | 取决于 `shenyu.upstreamCheck.enabled` 与周期 | 实时（依赖 Nacos 心跳周期，默认 ≤ 10s） |
| selector handle | `{divideUpstreams:[{upstreamUrl, weight, ...}]}` | `{serviceId, gray}` |
| ShenYu 对 Nacos 的依赖 | 0（注册走通道①） | 强（通道③，gRPC） |

> 生产实践参考：erpm-pay-center 选 divide + @ShenyuSpringMvcClient 走通道①，对 Nacos 零依赖；
> 本 demo 走通道③，演示完整的 Spring Cloud 服务发现闭环。
