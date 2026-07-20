# ShenYu 2.6.1 springCloud 插件 + Nacos 2.5.3 完整操作与调试记录

> 整理时间：2026-07-18
> 仓库：`D:\privategit\github\shenyu-client-java`
> 适用场景：在本地 Docker 环境搭建「Spring Cloud 微服务 + Nacos 2.5.3 + ShenYu 2.6.1 springCloud 插件」全链路
> 关联文档：[`springcloud-plugin-验证手册.md`](./springcloud-plugin-验证手册.md)（速查手册）、
> Docker 编排资源位置见 [`shenyu-springcloud-demo/README.md`](../shenyu-springcloud-demo/README.md) 的「相关资源」章节。

---

## 0. 摘要（TL;DR）

| 维度 | 锁定值 |
|---|---|
| **目标** | Spring Cloud 微服务经 ShenYu 网关 springCloud 插件路由，shenyu 通过 Nacos 发现实例 |
| **Nacos 版本** | **2.5.3**（2.5.x 末版，2026-07-14 发布，见 1.2 版本历史） |
| **ShenYu 版本** | 2.6.1 stock 镜像（admin + bootstrap） |
| **是否重编镜像** | **不需要**（stock bootstrap 已内置 springCloud 插件 + nacos-discovery starter） |
| **镜像来源** | 华为云 `swr.cn-north-4.myhuaweicloud.com/ddn-k8s/.../nacos-server:v2.5.3`（所有公共 mirror 都没同步 2.5.x 新版） |
| **业务侧 client 版本** | shenyu-spring-boot-starter-client-springcloud:2.6.1 + spring-cloud-starter-alibaba-nacos-discovery:2021.0.1.0 + nacos-client:2.0.4 |
| **关键约束** | (1) `shenyu.springCloudCache.enabled=false`（Nacos 必须为 false）<br>(2) `registerType: http`（2.6.1 唯一）<br>(3) demo 与 bootstrap 侧 namespace 必须一致（public = `""`） |
| **最终状态** | 全链路打通，复跑可重复 |

> **版本切换备注**：本文档第一版（2026-07-18 上午）使用 Nacos 2.5.1 跑通全链路，同日下午升级到 2.5.3（2.5.x 末版，4 天前刚发布）。两版本对 ShenYu 2.6.1 + nacos-client 2.0.4 的兼容性等价（同 minor 内补丁版），切换只需改容器名（`nacosserver251` → `nacosserver253`）、库名（`nacos_config_251` → `nacos_config_253`）、bootstrap compose env（`SERVER-ADDR=nacosserver253:8848`）。 |

---

## 1. 任务背景与初始认知修正

### 1.1 初始需求

在 `shenyu-client-java` 仓库搭建一个 Spring Cloud 子项目 demo，让 ShenYu 2.6.1 在 Nacos 微服务管理下通过 PluginList → Proxy → springCloud 插件实现网关路由。

参考材料：
- `D:\IdeaProjects\jzt\erpm-pay-center\docs\shenyu-2.6.1-springcloud-nacos-资料汇编.md`
- `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`
- `D:\privategit\gitee\docker-compose\Windows\nacos\docker-compose-nacos-2.3.1.yml`
- `D:\IdeaProjects\jzt\erpm-pay-center\build.gradle`（Gradle 依赖参考）
- 用户本地：Docker + Windows

### 1.2 关键认知修正（核实后与初始理解的差异）

| 项 | 初始理解 | 实际核实 | 出处 |
|---|---|---|---|
| Nacos 版本 | "升级到 2.5.3" | **2.5.3 确实存在**（2026-07-14 发布，4 天前刚出炉），升级到 2.5.3（2.5.x 末版） | nacos.io/download/release-history + GitHub alibaba/nacos releases |
| bootstrap 镜像 | "需要重编加 springCloud 插件" | **stock 镜像已内置** springCloud 插件 + nacos-discovery starter，仅被 conf 关闭 | apache/shenyu v2.6.1 tag `shenyu-bootstrap/pom.xml` |
| `@ShenyuSpringCloudClient` 属性 | "有 serviceName 属性" | **无 serviceName**，serviceId 取自 `spring.application.name`；推荐用组合注解 `@ShenyuRequestMapping`/`@ShenyuGetMapping` | apache/shenyu v2.6.1 source |
| admin 默认密码 | "123456" | **本环境为 `1qaz!QAZ`**（已改过），dashboard_user.password hash 与默认不同 | `dashboard_user` 表实测 |
| ext-lib 机制 | "可加任意 jar 到 bootstrap" | **2.6.1 的 entrypoint.sh 用 `-classpath conf:lib:ext-lib/`**，ext-lib 有效，但本方案不需要 | v2.6.1 entrypoint.sh |

#### Nacos 2.5.x 完整版本线（来源：[官方 release-history](https://nacos.io/download/release-history/)）

| 版本 | 发布日期 | Java 版本 |
|---|---|---|
| 2.5.0 | 2025-01-21 | Java 8 |
| 2.5.1 | 2025-03-11 | Java 8 |
| 2.5.2 | 2025-11-17 | Java 8 |
| **2.5.3** | **2026-07-14** | **Java 8** ← 本方案选用（2.5.x 末版，最新） |

> **核查方法备注**：本文档第一版（2026-07-18 上午）误判"Nacos 2.5.3 不存在"，原因是 Tavily 抓取的官方 release-history 页面在 `## Nacos 2.x` 标题后被截断，没拿到 2.5.3 行。**正确核查方式**：
> 1. `curl -s "https://api.github.com/repos/alibaba/nacos/releases?per_page=30"` —— GitHub releases 是最权威的源（含发布日期）
> 2. 直接试 `docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3`
> 3. 公共 docker mirror（xuanyuan/kejilion/1ms 等）对 2.5.x 新版本同步滞后，**不可**作为版本存在性的依据

### 1.3 通道划分（ShenYu 中 Nacos 承担的三个独立平面）

| 通道 | 配置位置 | 协议 | 本 demo 是否启用 |
|---|---|---|---|
| ① 元数据注册 | `shenyu.register.registerType: http` | HTTP → admin | ✅ 启用 |
| ② 配置同步 | `shenyu.sync.websocket` 或 `shenyu.sync.nacos` | websocket / nacos HTTP | ✅ 用 websocket（不用 nacos） |
| ③ 服务实例发现 | `spring.cloud.nacos.discovery`（client 注册、bootstrap 拉取） | Nacos gRPC | ✅ 启用 |

---

## 2. 最终架构与版本矩阵

### 2.1 拓扑

```
   curl /springcloud-demo/order/findById?id=42
                  │
                  ▼
   ┌──────────────────────────────┐
   │ shenyu-bootstrap-261 :9196   │ ◄── 数据面（流量入口）
   │  - springCloud 插件          │
   │  - NacosDiscoveryClient ─────┼──┐
   └──────────────────────────────┘  │
                  ▲                  │ gRPC 拉 instance
                  │ websocket        │
                  │ 同步 selector/rule│
   ┌──────────────────────────────┐  │
   │ shenyu-admin-261 :9096       │  │
   │  - admin/1qaz!QAZ            │  │
   │  - mysql shenyu_261          │  │
   └──────────────────────────────┘  │
                                     │
                  ┌──────────────────┘
                  ▼
   ┌──────────────────────────────┐
   │ nacosserver253 :8848 / :9848 │ ◄── 注册中心（2.5.x 末版）
   │  - mysql nacos_config_253    │
   │  - 鉴权关闭                   │
   └──────────────────────────────┘
                  ▲
                  │ gRPC register + heartbeat
                  │
   ┌──────────────────────────────┐
   │ shenyu-springcloud-demo :8470│ ◄── 业务侧 demo（mvn spring-boot:run）
   │  - @ShenyuRequestMapping     │
   │  - 双通道：http 注册 + nacos  │
   └──────────────────────────────┘
```

### 2.2 版本矩阵（已实测全链路兼容）

| 组件 | 版本 | 备注 |
|---|---|---|
| Nacos server | **2.5.3** | 容器 `nacosserver253`，库 `nacos_config_253`（2.5.x 末版，2026-07-14 发布） |
| ShenYu admin / bootstrap | **2.6.1 stock 镜像** | `apache/shenyu-admin:2.6.1` / `apache/shenyu-bootstrap:2.6.1` |
| shenyu-spring-boot-starter-client-springcloud | **2.6.1** | Maven Central 公开版 |
| spring-cloud-starter-alibaba-nacos-discovery | **2021.0.1.0** | ShenYu 2.6.1 内置版本 |
| spring-cloud-commons | **3.1.2** | ShenYu 2.6.1 内置版本 |
| nacos-client | **2.0.4** | 与镜像内一致；官方规则兼容 server 2.5.3 |
| Spring Boot（demo 侧） | **2.7.18** | demo 沿用本仓库惯例 |

### 2.3 网络与端口

| 容器 | 宿主端口 | 网络 |
|---|---|---|
| `nacosserver253` | 8848 / 9848 / 9849 | `nacos_net_232` + `mysql_default` |
| `shenyu-admin-261` | 9096 | `shenyu_net_261` + `mysql_default` |
| `shenyu-bootstrap-261` | 9196 | `shenyu_net_261` + `nacos_net_232`（patch 后新增） |
| `mysql57` | 3306 | `mysql_default` |
| demo（host 进程） | 8470 | 宿主机网络 |

---

## 3. 产出文件清单

全部新增，零修改既有业务代码。

### 3.1 业务侧 Maven 模块

```
shenyu-springcloud-demo/
├── pom.xml
└── src/main/
    ├── java/org/apache/shenyu/demo/springcloud/
    │   ├── ShenYuSpringCloudDemoApplication.java   @SpringBootApplication + @EnableDiscoveryClient
    │   └── controller/OrderController.java         @ShenyuRequestMapping + @ShenyuGetMapping/@ShenyuPostMapping
    └── resources/application.yml                   双通道配置：http 注册 + nacos discovery
```

**pom.xml 关键依赖**：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
</parent>

<properties>
    <shenyu.version>2.6.1</shenyu.version>
    <spring-cloud.version>3.1.2</spring-cloud.version>
    <spring-cloud-alibaba.version>2021.0.1.0</spring-cloud-alibaba.version>
    <nacos-client.version>2.0.4</nacos-client.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.apache.shenyu</groupId>
        <artifactId>shenyu-spring-boot-starter-client-springcloud</artifactId>
        <version>${shenyu.version}</version>
    </dependency>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        <version>${spring-cloud-alibaba.version}</version>
        <exclusions>
            <exclusion><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-context</artifactId></exclusion>
            <exclusion><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-commons</artifactId></exclusion>
        </exclusions>
    </dependency>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-commons</artifactId>
        <version>${spring-cloud.version}</version>
    </dependency>
    <dependency>
        <groupId>com.alibaba.nacos</groupId>
        <artifactId>nacos-client</artifactId>
        <version>${nacos-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

**application.yml 关键项**：

```yaml
server:
  port: 8470
spring:
  application:
    name: shenyu-springcloud-demo     # Nacos serviceId + admin selector handle.serviceId
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: ""                 # public namespace
        enabled: true
        # 鉴权关闭时不传 username/password（Nacos 2.5.x 仍校验用户）
shenyu:
  register:
    registerType: http
    serverLists: http://localhost:9096
    props:
      username: admin
      password: 1qaz!QAZ             # 与 admin 实际密码一致
  client:
    springCloud:
      props:
        contextPath: /springcloud-demo
        addPrefixed: false
```

### 3.2 Docker 编排资源（落在 gitee/docker-compose 仓库，不在本仓库内）

```
D:/privategit/gitee/docker-compose/Windows/nacos/
├── docker-compose-nacos-2.5.3.yml                             Nacos 2.5.3 容器（2.5.x 末版）
└── nacos_2.5.3/
    ├── conf/application.properties                            从 2.5.3 镜像 dump 的默认配置
    ├── init.d/custom.properties                               标准 metrics 配置（复用 2.3.2）
    ├── nacos-mysql.sql                                        从 2.5.3 镜像 dump 的 schema（179 行 10 表）
    └── logs/                                                  运行时日志（.gitignore 排除）

D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1/
└── docker-compose-ShenYu.yaml                                 shenyu-admin + bootstrap 编排
                                                               （bootstrap 服务已直接写入 nacos_net 接入 +
                                                                SPRING_CLOUD_DISCOVERY_* env，不再用 patch 文档）

D:/privategit/gitee/docker-compose/Windows/nacos/run.md        追加 2.5.3 启动命令 + 说明章节
```

### 3.3 文档

```
docs/
├── springcloud-nacos-完整操作与调试记录.md    ← 本文件
└── springcloud-plugin-验证手册.md             8 节速查手册
```

---

## 4. 详细操作步骤（按执行顺序）

### 阶段 0：环境前置检查

```shell
# 网络必须存在
docker network ls --format "{{.Name}}" | grep -E "mysql_default|shenyu_net_261|nacos_net_232"

# mysql57 / redis 等基础容器
docker ps --format "{{.Names}}" | grep -E "mysql57|redis"
```

### 阶段 1：搭建 Nacos 2.5.3

#### 1.1 拉取镜像（华为云 ddn-k8s 是唯一可用源）

```shell
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3
docker tag swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3 nacos/nacos-server:2.5.3
```

> **关键坑**：所有公共 mirror（xuanyuan/kejilion/1ms/daocloud/ixdev/mirrorify/yydy/vvvv/etcd 等）都不提供 `nacos:2.5.x` 新版本（2.5.1 / 2.5.2 / 2.5.3 都没同步），只同步了少数热门 tag。华为云 ddn-k8s 路径是拉到的唯一通道。
>
> **核查命令**（验证某版本是否真实存在，**不要**信 Tavily 抓取的 release-history 页面，会被截断）：
> ```shell
> curl -s "https://api.github.com/repos/alibaba/nacos/releases?per_page=30" | jq -r '.[] | "\(.published_at) | \(.tag_name)"'
> ```

#### 1.2 建库 `nacos_config_253`

```shell
docker exec mysql57 mysql -uroot -proot \
  -e "CREATE DATABASE IF NOT EXISTS nacos_config_253 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

#### 1.3 从镜像 dump schema 并导入

```shell
# Git Bash 需绕过路径转换
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint cat nacos/nacos-server:2.5.3 \
  /home/nacos/conf/mysql-schema.sql > /tmp/nacos-2.5.3-schema.sql
docker exec -i mysql57 mysql -uroot -proot nacos_config_253 < /tmp/nacos-2.5.3-schema.sql

# 验证：10 张表
docker exec mysql57 mysql -uroot -proot -e "USE nacos_config_253; SHOW TABLES;"
```

> 注意：2.5.3 schema 与 2.5.1 完全一致（179 行 10 表），无 `CREATE DATABASE` 语句（库由 env 指定）。

#### 1.4 启动 nacos 2.5.3

```shell
cd D:/privategit/gitee/docker-compose/Windows/nacos
docker-compose -f docker-compose-nacos-2.5.3.yml -p nacos253 up -d
```

compose 关键项：
- `container_name: nacosserver253`
- `MYSQL_SERVICE_DB_NAME=nacos_config_253`
- 网络：`nacos_net_232` + `mysql_default`（external）
- 鉴权关闭：`NACOS_AUTH_ENABLE=false`
- 不挂载 `conf/application.properties`（避免 bind mount 创建空目录覆盖镜像默认）

#### 1.5 验证

```shell
curl -s http://127.0.0.1:8848/nacos/v1/console/health/readiness    # "OK"
curl -s "http://127.0.0.1:8848/nacos/v1/ns/operator/metrics"       # {"status":"UP"}
```

### 阶段 2：配置 ShenYu bootstrap 接入 Nacos 网络

#### 2.1 编辑 gitee 仓库的 docker-compose-ShenYu.yaml

文件：`D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`

**改动 1**：`services.shenyu-bootstrap.environment` 添加（在既有 GW_SIGN_* 后）

```yaml
      # ====== springCloud 插件 + Nacos discovery ======
      - SPRING_CLOUD_DISCOVERY_ENABLED=true
      - SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=true
      - SPRING_CLOUD_NACOS_DISCOVERY_SERVER-ADDR=nacosserver253:8848
      - SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=
      # 注意：不传 USERNAME/PASSWORD（Nacos 2.5.x 鉴权关闭时仍校验用户，传了反而报错）
      - SHENYU_SPRINGCLOUDCACHE_ENABLED=false
```

**改动 2**：`services.shenyu-bootstrap` 下添加 networks 块

```yaml
    networks:
      - default       # shenyu_net_261（与 admin 互通）
      - nacos_net     # nacos_net_232（与 nacosserver253 互通）
```

**改动 3**：顶层 networks 添加

```yaml
  nacos_net:
    name: nacos_net_232
    external: true
```

#### 2.2 重建 bootstrap（重启无效，必须 stop+rm+up）

```shell
docker stop shenyu-bootstrap-261
docker rm shenyu-bootstrap-261
cd D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1
docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d --no-deps shenyu-bootstrap
```

> **坑**：`docker restart` 不会重读 compose 配置；`docker-compose up` 若 depends_on 触发 admin 重建会冲突。必须 `--no-deps` + 先 `docker stop/rm`。

#### 2.3 验证

```shell
# 网络接入
docker inspect shenyu-bootstrap-261 --format '{{range $net, $cfg := .NetworkSettings.Networks}}{{$net}}{{println}}{{end}}'
# 期望：nacos_net_232 + shenyu_net_261 两行

# 容器名解析 + HTTP 连通
docker exec shenyu-bootstrap-261 sh -c "wget -q -O - http://nacosserver253:8848/nacos/v1/ns/operator/metrics"
# 期望 {"status":"UP"}
```

### 阶段 3：admin 控制台配置

#### 3.1 拿 admin token

```shell
curl -s -G 'http://127.0.0.1:9096/platform/login' \
  --data-urlencode 'userName=admin' \
  --data-urlencode 'password=1qaz!QAZ' > /tmp/login.json
TOKEN=$(jq -r '.data.token' /tmp/login.json)
```

#### 3.2 确认 springCloud 插件已 enabled

```shell
curl -s "http://127.0.0.1:9096/plugin?currentPage=1&pageSize=100" -H "X-Access-Token: $TOKEN" \
  | jq -r '.data.dataList[] | select(.name=="springCloud") | "\(.id) | enabled=\(.enabled)"'
# 期望：8 | enabled=true
```

> 若为 false：admin 控制台「插件管理」开启，或 PUT `/plugin/{id}`。
> **selector / rule 无需手工创建**——demo 启动时会自动通过 HTTP 注册推到 admin。

### 阶段 4：启动 demo 业务服务

#### 4.1 后台启动

```shell
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
nohup mvn -B -DskipTests spring-boot:run > /tmp/demo.log 2>&1 &
echo $! > /tmp/demo.pid
```

#### 4.2 等 15 秒，验证 3 段关键日志

```shell
# 等 Started
for i in $(seq 1 18); do
  sleep 5
  grep -q "Started ShenYuSpringCloudDemoApplication" /tmp/demo.log && break
done

# 证据 1：Nacos 注册
grep "register finished" /tmp/demo.log | tail -1
# 期望：nacos registry, DEFAULT_GROUP shenyu-springcloud-demo <IP>:8470 register finished

# 证据 2：admin 登录
grep "login success" /tmp/demo.log | tail -1
# 期望：login success: {...token...}

# 证据 3：metadata 推送 3 条
grep -c "metadata client register success" /tmp/demo.log
# 期望：3
```

### 阶段 5：全链路验证

```shell
curl -s "http://127.0.0.1:9196/springcloud-demo/order/findById?id=42"
# 期望：{"instanceId":"...","name":"springcloud-demo-order-42","id":"42",...}

curl -s "http://127.0.0.1:9196/springcloud-demo/order/hello"
curl -s -X POST -H "Content-Type: text/plain" -d '{"k":"v"}' \
  "http://127.0.0.1:9196/springcloud-demo/order/echo-body"
```

---

## 5. 调试过程（5 个关键坑及解法）

### 5.1 坑 1：拉不到 `nacos:2.5.x` 镜像

**现象**：
```
docker pull nacos/nacos-server:2.5.3
# Error: 429 Too Many Requests (xuanyuan.me)
# 各 mirror 都返回 not found 或 403
```

**排查**：依次测试所有配置的 mirror（xuanyuan/kejilion/1ms/daocloud/ixdev/mirrorify/yydy/vvvv/etcd），所有公开 mirror 都没同步 2.5.x 新版本（2.5.1 / 2.5.2 / 2.5.3 都拉不到）。

**根因**：公共 mirror 只同步 latest + 少数热门 tag，新版 nacos 2.5.x 没被同步。

**解法**：用本地已有 nacos 镜像的同源路径（华为云 ddn-k8s），发现 `v2.5.3`（带 v 前缀）在它上面：
```shell
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3
docker tag swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3 nacos/nacos-server:2.5.3
```

**附带教训**：本次核实版本是否存在时，错误地信任了 Tavily 抓取的 `nacos.io/download/release-history/` 页面（被截断在 `## Nacos 2.x` 标题后，只看到 2.5.1 是最新的 2.5.x），导致误判"Nacos 2.5.3 不存在"。**正确核查方法**：
1. `curl -s "https://api.github.com/repos/alibaba/nacos/releases?per_page=30" | jq -r '.[] | "\(.published_at) | \(.tag_name)"'` ← 最权威
2. 直接试 `docker pull swr.cn-north-4.myhuaweicloud.com/.../nacos-server:v2.5.3`
3. **不要**把公共 mirror 的"未同步"当成"不存在"

### 5.2 坑 2：Nacos 2.5.x `User nacos not found` 报错噪音

**现象**：demo / bootstrap 启动后日志反复出现：
```
ERROR c.a.nacos.client.security.SecurityProxy - login failed:
  {"code":500,"message":"caused: User nacos not found;",...}
```
即使 `NACOS_AUTH_ENABLE=false` 也报。

**根因**：Nacos 2.5.x 即便鉴权关闭，server 仍会对未定义用户名返回 "User xxx not found"。client 的 SecurityProxy 每次启动都尝试 login。

**解法**：鉴权关闭时**不传 username/password**，让 client 走匿名（不调 login 接口）：
- bootstrap 侧：compose env 不加 `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME/PASSWORD`
- demo 侧：`application.yml` 中 `spring.cloud.nacos.discovery` 不写 username/password

> 注：这个错误是噪音，不影响实际服务发现（gRPC 心跳正常）。

### 5.3 坑 3：bootstrap 解析不到 `nacosserver253`

**现象**：
```
docker exec shenyu-bootstrap-261 sh -c "wget ... http://nacosserver253:8848/..."
# wget: unable to resolve host address 'nacosserver253'
```

**根因**：stock bootstrap 容器默认只在 `shenyu_net_261` 网络上，不在 `nacos_net_232`，无法按容器名解析 nacos。

**解法**：compose patch 给 `services.shenyu-bootstrap.networks` 加 `- nacos_net`（external `nacos_net_232`），顶层 networks 加声明。

### 5.4 坑 4：`-107 divide:Can not find selector`

**现象**：
```shell
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
# {"code":-107,"message":"divide:Can not find selector, please check your configuration!"}
```
明明 springCloud selector 已通过 admin REST API 创建。

**排查**：bootstrap 日志：
```
ERROR org.apache.shenyu.plugin.api.utils.WebFluxResultUtils -
  can not match selector data: divide , path is /springcloud-demo/order/findById
```
说明 divide 插件兜底返回 -107，springCloud 没匹配上 selector。

**根因**：demo 的 metadata 没成功推到 admin（admin 密码错）。日志：
```
ERROR o.a.s.r.c.h.HttpClientRegisterRepository -
  Register admin url :http://localhost:9096 is fail, will retry. cause:accessToken is null
```
没有 metadata → springCloud selector 无法识别 path 归属 → 不匹配 → divide 兜底 -107。

**根因的根因**：admin 密码本环境为 `1qaz!QAZ`（非默认 123456），demo application.yml 配的是 123456。

**解法**：
1. 改 demo `application.yml`：`shenyu.register.props.password: 1qaz!QAZ`
2. 重启 demo
3. 看 demo 日志确认 `metadata client register success` 出现 3 条
4. SQL 验证：`SELECT * FROM shenyu_261.meta_data WHERE app_name='shenyu-springcloud-demo';`（应有 3 行）

> **重要发现**：demo 启动时 `shenyu-spring-boot-starter-client-springcloud` 会**自动通过 HTTP 把 selector + rule + meta_data 推到 admin**，无需手工配。手工配 selector 反而容易踩坑（见 5.5）。

### 5.5 坑 5：`pathMatch name is error`

**现象**：
```shell
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
# {"code":400,"message":"pathMatch name is error"}
```

**排查**：bootstrap 日志 stacktrace：
```
java.lang.IllegalArgumentException: pathMatch name is error
    at org.apache.shenyu.spi.ExtensionLoader.createExtension(ExtensionLoader.java:197)
    at org.apache.shenyu.plugin.base.condition.judge.PredicateJudgeFactory.newInstance(PredicateJudgeFactory.java:42)
```
PredicateJudgeFactory 在加载 `pathMatch` SPI 时失败。

**根因**：我手工创建 selector / rule 时用了 `operator: pathMatch`，**ShenYu 2.6.1 不存在这个 SPI**。支持的 operator：`startsWith` / `endsWith` / `=` / `pathPattern` / `regex` / `match` / `contains` / `exclude` / `Groovy` / `SpEL` / `TimeBefore` / `TimeAfter` / `path`。

**解法**：
1. SQL 清理残留：
   ```sql
   DELETE FROM shenyu_261.rule_condition WHERE operator='pathMatch';
   DELETE FROM shenyu_261.selector_condition WHERE operator='pathMatch';
   ```
2. **重启 bootstrap 清内存缓存**（admin 推送的 DELETE 事件不一定让 bootstrap 立即同步）：
   ```shell
   docker restart shenyu-bootstrap-261
   ```
3. 之后让 demo 自动注册的 selector/rule 接管（用 `startsWith`）。

### 5.6 排查工具箱

| 想看什么 | 命令 |
|---|---|
| admin 当前所有插件 | `curl -s .../plugin?currentPage=1&pageSize=100 -H "X-Access-Token: $TOKEN" \| jq` |
| 某 selector 详情 | `curl -s .../selector/{id} -H "X-Access-Token: $TOKEN" \| jq` |
| bootstrap 收到的 selector 推送 | `docker logs shenyu-bootstrap-261 \| grep "subscribe select data"` |
| bootstrap 收到的 rule 推送 | `docker logs shenyu-bootstrap-261 \| grep "subscribe rule data"` |
| 哪个 selector 匹配上了请求 | `docker logs shenyu-bootstrap-261 \| grep "rule success match"` |
| 错误堆栈 | `docker logs --since 10s shenyu-bootstrap-261 \| grep -A 20 "ERROR"` |
| SQL 查 metadata | `SELECT app_name,path,rpc_type FROM shenyu_261.meta_data WHERE app_name='shenyu-springcloud-demo';` |
| SQL 查 springCloud selector | `SELECT s.id,s.name FROM selector s JOIN plugin p ON s.plugin_id=p.id WHERE p.name='springCloud';` |
| Nacos 实例列表 | `curl -s ".../nacos/v1/ns/instance/list?serviceName=shenyu-springcloud-demo" \| jq` |
| Nacos 上下线实例 | `curl -X PUT ".../nacos/v1/ns/instance?serviceName=...&ip=...&port=...&enabled=false"` |

---

## 6. 故障切换验证（证明走 springCloud 委托器路径）

这一步是与 divide 路径的关键区别证明。

```shell
# (1) 先 curl 记录正常响应的 instanceId
curl -s "http://127.0.0.1:9196/springcloud-demo/order/findById?id=1" | jq .instanceId

# (2) Nacos 下线实例
curl -s -X PUT "http://127.0.0.1:8848/nacos/v1/ns/instance?serviceName=shenyu-springcloud-demo&ip=192.168.124.19&port=8470&enabled=false"
# 期望：ok

# (3) 等 15 秒心跳生效
sleep 15

# (4) 再 curl，期望 springCloud 插件返回 -109（不是 divide 的 -107）
curl -s "http://127.0.0.1:9196/springcloud-demo/order/findById?id=2"
# 期望：{"code":-109,"message":"SpringCloud serviceId does not exist or is configured incorrectly!"}

# (5) 恢复实例
curl -s -X PUT "http://127.0.0.1:8848/nacos/v1/ns/instance?serviceName=shenyu-springcloud-demo&ip=192.168.124.19&port=8470&enabled=true"
sleep 15
curl -s "http://127.0.0.1:9196/springcloud-demo/order/findById?id=3"
# 期望：200，instanceId 与步骤 1 相同（同一 JVM）
```

**含义**：
- `-109` 是 springCloud 插件的错误码（不是 divide 的 -107），证明 springCloud 委托器路径在工作
- NacosDiscoveryClient 实时感知实例下线，每次请求现拉实例列表
- 与 divide 的"ShenYu 自管 upstream + 探活"路径形成对比

---

## 7. 复跑（机器重启后）

### 7.1 全冷启动（docker 全停过）

依赖链：`mysql → nacos → shenyu-admin → shenyu-bootstrap → demo`

```shell
# 1) mysql
cd D:/privategit/gitee/docker-compose/Windows/mysql
docker-compose -f docker-compose-mysql5.7.yml -p mysql up -d

# 2) nacos 2.5.3
cd D:/privategit/gitee/docker-compose/Windows/nacos
docker-compose -f docker-compose-nacos-2.5.3.yml -p nacos253 up -d

# 3) shenyu-admin + bootstrap
cd D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1
docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d

# 4) 等 admin healthy
for i in $(seq 1 36); do
  sleep 5
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://127.0.0.1:9096/actuator/health)
  [ "$code" = "200" ] && { echo "admin UP"; break; }
done

# 5) 启 demo
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
mvn spring-boot:run

# 6) 验证
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
```

### 7.2 部分启动（docker 还在，只重启 demo）

```shell
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
mvn spring-boot:run
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
```

### 7.3 demo 重启（kill java 进程）

```shell
# 找真实 java PID（mvn PID 不是 java PID）
netstat -ano | grep ":8470" | grep LISTENING
# 假设得到 PID 49868
taskkill //PID 49868 //F

# 等 8 秒确认 springCloud 实时感知
sleep 8
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
# 期望：{"code":-109,...}（实例已下线）

# 重启
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
nohup mvn -B -DskipTests spring-boot:run > /tmp/demo.log 2>&1 &
```

---

## 8. 架构本质澄清

### 8.1 两个 shenyu 容器的职责（不是重复启动）

| 容器 | 角色 | 端口 | 职责 |
|---|---|---|---|
| `shenyu-admin-261` | **控制面** | 9096 | 配置中心：存放 selector/rule/plugin/meta_data，Web UI + REST API + 给 bootstrap 推配置的 websocket |
| `shenyu-bootstrap-261` | **数据面** | 9196 | 真正处理流量的入口：跑插件链 + 转发到后端 |

```
   你 curl 9196 ──► shenyu-bootstrap-261  （跑 springCloud 插件，转发到 demo:8470）
                          ▲
                          │ websocket 同步 selector/rule/plugin 配置
   你配 admin UI ──► shenyu-admin-261    （读写 mysql shenyu_261 库里的配置）
```

**为什么不能合并**：
1. admin 是 Spring MVC 同步应用，bootstrap 是 WebFlux + Netty 反应式应用，技术栈不同
2. 生产场景 1 个 admin + N 个 bootstrap 做高可用
3. 配置热更新靠 admin → websocket → bootstrap 异步推送
4. 故障隔离：admin 挂了 bootstrap 仍能按内存配置转发

### 8.2 springCloud 委托器 vs divide 单选器

| 维度 | divide（既有 http-demo） | springCloud（本 demo） |
|---|---|---|
| 客户端注解 | `@ShenyuSpringMvcClient` | `@ShenyuSpringCloudClient`（或组合注解 `@ShenyuRequestMapping` 等） |
| upstream 来源 | 客户端 HTTP 注册推 admin → bootstrap UpstreamCacheManager 内存+DB | NacosNamingService 维护，bootstrap 每次 `getInstances(serviceId)` 现拉 |
| 探活 | ShenYu 自身 UpstreamCheckManager | Nacos 心跳（实例自身 push） |
| 实例下线感知 | 取决于 shenyu.upstreamCheck | 实时（依赖 Nacos 心跳周期，默认 ≤ 10s） |
| selector handle | `{divideUpstreams:[{upstreamUrl, weight, ...}]}` | `{serviceId, gray}` |
| ShenYu 对 Nacos 的依赖 | 0 | 强（gRPC） |
| 错误码 | -107 | -109 |

---

## 9. 常见错误对照表

| 错误 | 根因 | 解法 |
|---|---|---|
| 拉不到 `nacos:2.5.x` | 公共 mirror 都没同步 2.5.x 新版（2.5.1/2.5.2/2.5.3） | 华为云 ddn-k8s 路径 |
| `User nacos not found` 噪音 | Nacos 2.5.x 鉴权空仓 | 客户端不传 username/password |
| `-107 divide:Can not find selector` | demo metadata 没推到 admin | 改 demo yml 的 admin 密码 |
| `-109 SpringCloud serviceId does not exist` | Nacos 上实例下线 / serviceId 不匹配 | 检查 `spring.application.name` 与 selector handle.serviceId |
| `pathMatch name is error` | 用了不存在的 operator | SQL 清理 + 重启 bootstrap，改用 `startsWith`/`pathPattern`/`=` |
| bootstrap 解析不到 nacos 容器 | 没接入 nacos_net_232 | compose patch 加 networks |
| compose 改 env 不生效 | docker restart 不重读配置 | `docker stop/rm` + `docker-compose up -d --no-deps --force-recreate` |
| `accessToken is null` 推 admin 失败 | admin 密码不对 | demo yml 改正确密码 |
| `depends_on service_healthy` 冲突 | up 触发 admin 重建但 admin 已在 | 加 `--no-deps` 或先 stop/rm 目标容器 |
| 误判 Nacos 某版本不存在 | Tavily 抓取 release-history 被截断 | 用 GitHub releases API 核实 |

---

## 10. 修改的文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `shenyu-springcloud-demo/`（新增整个模块） | 新增 | 业务侧 Maven 模块（pom + 业务代码 + 测试 + .http 用例 + README） |
| `docs/springcloud-plugin-验证手册.md` | 新增 | 8 节速查手册 |
| `docs/springcloud-nacos-完整操作与调试记录.md` | 新增 | 本文件 |
| `D:\privategit\gitee\docker-compose\Windows\nacos\docker-compose-nacos-2.5.3.yml` | **新增（外部 gitee 仓库）** | Nacos 2.5.3 容器编排 |
| `D:\privategit\gitee\docker-compose\Windows\nacos\nacos_2.5.3\` | **新增（外部 gitee 仓库）** | nacos 配置目录（conf / init.d / nacos-mysql.sql） |
| `D:\privategit\gitee\docker-compose\Windows\nacos\run.md` | **修改（外部 gitee 仓库）** | 追加 2.5.3 启动命令 + 说明章节 |
| `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml` | **修改（外部 gitee 仓库）** | bootstrap 服务加 networks（nacos_net） + SPRING_CLOUD_DISCOVERY_* env，直接写入 compose 本体（非 patch 文档） |

---

## 11. 当前运行环境快照（2026-07-18 实测）

| 容器/进程 | 镜像/版本 | 状态 |
|---|---|---|
| `nacosserver253` | nacos/nacos-server:2.5.3 | Up |
| `shenyu-admin-261` | apache/shenyu-admin:2.6.1 | Up (healthy) |
| `shenyu-bootstrap-261` | apache/shenyu-bootstrap:2.6.1 | Up (healthy) |
| `mysql57` | mysql:5.7 | Up |
| demo 进程 | mvn spring-boot:run (JDK 8) | 端口 8470，jar 52M |

数据库：
- `shenyu_261`：admin 配置库，含 springCloud selector (id=2078451830207381504) + 3 条 rule + 3 条 meta_data
- `nacos_config_253`：nacos 2.5.3 配置库，10 张表
- 历史 nacos 库（`nacos_config` / `_223` / `_231` / `_251`）已清理

网关入口：
```shell
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
# {"instanceId":"...","name":"springcloud-demo-order-42","id":"42",
#  "source":"shenyu-springcloud-demo","serverPort":"8470","timestamp":...}
```

---

## 12. 参考资料

- ShenYu 2.6.1 springCloud 插件官方文档：<https://shenyu.apache.org/docs/2.6.1/plugin-center/proxy/spring-cloud-plugin>
- ShenYu 2.6.1 release notes（register 仅支持 http）：<https://shenyu.apache.org/docs/next/user-guide/property-config/register-center-access/>
- Nacos 版本兼容性 FAQ：<https://nacos.io/en/blog/faq/nacos-user-question-history8233>
- v2.6.1 `shenyu-bootstrap/pom.xml`：<https://github.com/apache/shenyu/blob/v2.6.1/shenyu-bootstrap/pom.xml>
- v2.6.1 `@ShenyuSpringCloudClient` 源码：<https://github.com/apache/shenyu/blob/v2.6.1/shenyu-client/shenyu-client-http/shenyu-client-springcloud/src/main/java/org/apache/shenyu/client/springcloud/annotation/ShenyuSpringCloudClient.java>
- v2.6.1 官方 springCloud 示例：<https://github.com/apache/shenyu/blob/v2.6.1/shenyu-examples/shenyu-examples-springcloud/>

---

## 13. 清理历史 nacos 资产（2026-07-18）

按"每个 major.minor 仅保留最高 patch"原则，把 2.5.1 / 2.3.1 / v2.0.3 等历史小版本清理掉。

### 13.1 保留档（5 档）

| 大版本 | 保留小版本 |
|---|---|
| 1.4.x | 1.4.1 |
| 2.0.x | 2.0.4 |
| 2.2.x | 2.2.3 |
| 2.3.x | v2.3.2 |
| 2.5.x | **2.5.3**（当前在用） |

### 13.2 删除的资产

| 类别 | 删除项 |
|---|---|
| 本地容器 | `nacosserver223`（已 Exited 4h 的 2.2.3 残留容器） |
| 本地镜像 tag | `nacos/nacos-server:v2.0.3`、`nacos/nacos-server:2.3.1`、`nacos/nacos-server:2.5.1` + 华为云源 `v2.3.1`、`v2.5.1`（v2.0.3 本来就只是 tag 无 layer） |
| MySQL 库 | `nacos_config`（默认库，旧 2.0.x 残留）、`nacos_config_223`、`nacos_config_231`、`nacos_config_251` |
| 业务模块下临时目录 | `shenyu-springcloud-demo/nacos-2.5.1/`（前序会话临时放在业务模块下的 nacos 编排，正式版迁到 gitee 后已删除；保留 2.5.3 即可） |

### 13.3 删除命令记录

```shell
# 1) 容器
docker rm nacosserver223

# 2) 镜像（本地 tag + 华为云源 tag）
docker rmi nacos/nacos-server:v2.0.3 nacos/nacos-server:2.3.1 nacos/nacos-server:2.5.1
docker rmi swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.3.1
docker rmi swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.1

# 3) MySQL 库
docker exec mysql57 mysql -uroot -proot -e \
  "DROP DATABASE IF EXISTS nacos_config; DROP DATABASE IF EXISTS nacos_config_223; \
   DROP DATABASE IF EXISTS nacos_config_231; DROP DATABASE IF EXISTS nacos_config_251;"

# 4) 业务模块下的临时 nacos 编排目录（前序会话临时放，迁到 gitee 后已删）
rm -rf shenyu-springcloud-demo/nacos-2.5.1
```

> 注：gitee 仓库 `docker-compose/Windows/nacos/` 下的历史 compose 文件（1.4.1/2.0.4/2.2.3/2.3.1/2.3.2）**未动**，
> 那是外部共享仓库，由该仓库自行决定保留策略。本仓库的清理范围仅限上述四类。

### 13.4 删除后验证

```shell
docker ps -a --filter "name=nacos"  # 仅剩 nacosserver253 (Up)
docker image ls | grep nacos        # 4 个本地 tag（2.0.4 / 2.2.3 / v2.3.2 / 2.5.3）
docker exec mysql57 mysql -uroot -proot -e "SHOW DATABASES LIKE 'nacos%';"  # 仅 nacos_config_253
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42  # HTTP 200
```
