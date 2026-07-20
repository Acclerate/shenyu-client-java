# shenyu-springcloud-demo

> Spring Cloud 微服务接入 ShenYu 2.6.1 网关的最小可运行 demo，服务注册到 Nacos，经网关 **springCloud 插件**路由转发。
> 与同仓库 [`../shenyu-http-demo`](../shenyu-http-demo)（走 **divide 插件** + HTTP 元数据注册）形成对照，演示「springCloud 委托器」路径。

## 官方文档映射

本模块严格参照 Apache ShenYu 2.6.1 官方文档与官方示例实现，对应关系：

| 本模块 | 官方文档 / 示例 |
|---|---|
| 整体接入方式 | [Spring Cloud 服务接入](https://shenyu.apache.org/zh/docs/2.6.1/user-guide/proxy/spring-cloud-proxy/) |
| springCloud 插件配置 | [Spring Cloud 插件](https://shenyu.apache.org/zh/docs/2.6.1/plugin-center/proxy/spring-cloud-plugin/) |
| 快速开始 | [Spring Cloud 快速开始](https://shenyu.apache.org/zh/docs/2.6.1/quick-start/quick-start-springcloud/) |
| `OrderController` 4 接口面 | [shenyu-examples-springcloud OrderController.java (v2.6.1)](https://github.com/apache/shenyu/blob/v2.6.1/shenyu-examples/shenyu-examples-springcloud/src/main/java/org/apache/shenyu/examples/springcloud/controller/OrderController.java) |
| `OrderDTO` | 官方 examples-common 中的同名 DTO（简化版，仅保留 id + name） |
| `application.yml`（双通道配置） | 官方 examples-springcloud 的 application.yml |

## 接口面

| 方法 | 路径 | 入参 | 验证场景 | 备注 |
|---|---|---|---|---|
| POST | `/order/save` | `OrderDTO` JSON body | 网关对 JSON 请求体的透传 + 后端反序列化 | 官方 demo 同名接口 |
| GET | `/order/findById?id=xxx` | `@RequestParam` | query string 透传 + 参数绑定 | 官方 demo 同名接口 |
| GET | `/order/path/{id}/{name}` | 多 `@PathVariable` | 多段路径变量绑定 | 官方 demo 同名接口 |
| GET | `/order/path/{id}/name` | 单 `@PathVariable` + 固定段 | 半 restful 路径模板 | 官方 demo 同名接口 |
| POST | `/order/echo-body` | raw string body | raw 请求体字节透传 | 本仓库额外补充的边界用例 |

## 与官方 demo 的差异

| 维度 | 官方 shenyu-examples-springcloud | 本 demo |
|---|---|---|
| 注册中心 | 同时引入 eureka-client + nacos-discovery（运行时切换） | **仅用 nacos**（与本地环境对齐） |
| contextPath | `/springcloud` | `/springcloud-demo`（避免与官方 demo 路径冲突） |
| serviceId | `springCloud-test` | `shenyu-springcloud-demo` |
| 端口 | 8884 | 8470 |
| admin 密码 | `admin/123456` | `admin/1qaz!QAZ`（本环境实际密码） |
| Lombok | 引入（`@Data`） | 不引入（getter/setter 手写，避免单模块加依赖） |
| `spring.cloud.discovery.enabled` | 显式 `true` | 显式 `true`（对齐官方强调） |
| `shenyu.springCloudCache.enabled` | 文档强调 false | bootstrap 侧 env 注入 `false`（对齐官方强制） |

## 架构与版本

```
   curl 9196 ─► shenyu-bootstrap-261 ─► NacosDiscoveryClient ─► nacosserver253
                       │                                              ▲
                       │ websocket 同步 selector/rule                 │ gRPC register + heartbeat
                       ▼                                              │
                shenyu-admin-261                            shenyu-springcloud-demo:8470
                       │                                              ▲
                       │ HTTP 元数据注册（path/rpcType=springCloud）  │
                       └──────────────────────────────────────────────┘
```

| 组件 | 版本 |
|---|---|
| ShenYu admin / bootstrap | 2.6.1（stock 镜像，stock 已内置 springCloud 插件 + nacos-discovery） |
| shenyu-spring-boot-starter-client-springcloud | 2.6.1（Maven Central） |
| spring-cloud-starter-alibaba-nacos-discovery | 2021.0.1.0（与 ShenYu 2.6.1 内置版本一致） |
| spring-cloud-commons | 3.1.2（同上） |
| nacos-client | 2.0.4（同上；官方规则兼容 server 2.5.3） |
| Nacos server | 2.5.3（编排位置见下方「相关资源」） |
| Spring Boot（demo 侧） | 2.7.18 |

## 构建与运行

### 编译

```shell
cd shenyu-springcloud-demo
mvn -B -DskipTests compile
```

### 单元测试（不需要 Nacos / admin）

```shell
mvn test
# 期望：Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

测试覆盖：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `OrderControllerTest` | 6 | `@WebMvcTest` + MockMvc，覆盖 5 个接口 + echo-body 边界 |
| `OrderDTOTest` | 5 | POJO 的 getter/setter/equals/hashCode/toString |

### 启动（依赖外部 docker 环境）

前置：`mysql57` + `nacosserver253` + `shenyu-admin-261` + `shenyu-bootstrap-261` 已运行
（docker 编排位置见下方「相关资源」）。

```shell
mvn spring-boot:run
# 或 IDEA 直接运行 ShenYuSpringCloudDemoApplication
```

启动成功的 3 段日志证据（缺一不可）：

```
INFO  c.a.c.n.registry.NacosServiceRegistry    : nacos registry, DEFAULT_GROUP shenyu-springcloud-demo <IP>:8470 register finished
INFO  o.a.s.r.client.http.utils.RegisterUtils  : login success: {...token...}
INFO  o.a.s.r.client.http.utils.RegisterUtils  : metadata client register success: {...path...}    × 5 条
```

## 验证

### IDEA HTTP Client（推荐）

打开 `src/main/resources/http/shenyu-springcloud-demo.http`，逐个 Run。覆盖：

1. demo 直连（端口 8470，绕过网关）
2. 经网关（端口 9196，走 springCloud 插件）
3. 故障切换（Nacos 下线实例 → 网关返回 `-109`）

### curl

```shell
# 直连
curl http://127.0.0.1:8470/order/findById?id=42

# 经网关
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
curl -X POST http://127.0.0.1:9196/springcloud-demo/order/save \
     -H "Content-Type: application/json" \
     -d '{"id":"100","name":"raw-name"}'
curl http://127.0.0.1:9196/springcloud-demo/order/path/42/alice
```

### SQL 验证（只查询，不写）

```shell
docker exec mysql57 mysql -uroot -proot shenyu_261 -e "
  SELECT s.name, p.name AS plugin FROM selector s JOIN plugin p ON s.plugin_id=p.id WHERE p.name='springCloud';
  SELECT app_name, path, rpc_type FROM meta_data WHERE app_name='shenyu-springcloud-demo';
"
```

期望：
- selector: 1 行（`name=/springcloud-demo, plugin=springCloud`）
- meta_data: 5 行（`rpc_type=springCloud`）

## 常见问题

### 1. `divide:Can not find selector` (code -107)

根因：demo metadata 没成功推到 admin（admin 密码错）。修 `application.yml`：
```yaml
shenyu.register.props.password: 1qaz!QAZ   # 与 admin 实际密码一致
```

### 2. `User nacos not found` 日志噪音

Nacos 2.5.x 鉴权空仓校验。鉴权关闭时不传 `username/password`（本模块 `application.yml` 已注释掉）。

### 3. `pathMatch name is error` (code 400)

手工创建 selector 时用了不存在的 operator `pathMatch`。改用 `startsWith` / `pathPattern` / `=`。
（本模块不手工建 selector，demo 自动注册时用 `startsWith`，正常工作。）

### 4. 详细排查

见 [`../docs/springcloud-plugin-验证手册.md`](../docs/springcloud-plugin-验证手册.md) 的 g 章节。

## 关键约束（来自官方文档）

1. **`shenyu.springCloudCache.enabled` 必须为 `false`** —— 用 Nacos/Eureka 时强制，每次心跳现拉实例列表（官方 2.6.1 文档 §2.5.4）。
2. **`shenyu.register.registerType` 仅支持 `http`** —— 2.6.1 起移除了中间件注册类型（官方 register-center-access 文档）。
3. **`spring.cloud.discovery.enabled: true`** —— 官方文档强调必开。
4. **serviceId 三处一致** —— `spring.application.name` == Nacos 服务名 == admin selector handle.serviceId。
5. **使用 ShenYu 自有 `shenyu-loadbalancer`** —— 2.5.0 起不再依赖 Ribbon，rule handle 的 `loadBalance` 取值 `roundRobin` / `random` / `hash`。

## 未来扩展（不在本 demo 范围）

- **灰度发布**：双实例 + selector 的 `gray=true` + header 条件路由（官方 2.5.2 节）
- **访问未注册到 ShenYu 的服务**：通过 `rpc_type: springCloud` 请求头（官方 2.6.2.5.1 节）
- **多实例负载均衡观测**：启动 2 个 demo 实例（不同端口），用 `instanceId` 字段观察 roundRobin 分发

## 相关资源（不在本模块内）

> 本模块仅包含业务侧代码。Docker 编排、配置补丁等基础设施资源存放位置：

| 资源 | 位置 |
|---|---|
| Nacos 2.5.3 docker-compose | gitee 仓库 `D:\privategit\gitee\docker-compose\Windows\nacos\docker-compose-nacos-2.5.3.yml` |
| Nacos 2.5.3 子目录（conf/init.d/nacos-mysql.sql） | gitee 仓库 `D:\privategit\gitee\docker-compose\Windows\nacos\nacos_2.5.3\` |
| ShenYu 2.6.1 docker-compose（admin + bootstrap） | gitee 仓库 `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`（已内置 springCloud 插件 + nacos discovery 配置） |
| 端到端验证手册 | 本仓库 [`../docs/springcloud-plugin-验证手册.md`](../docs/springcloud-plugin-验证手册.md) |
| 完整操作与调试记录 | 本仓库 [`../docs/springcloud-nacos-完整操作与调试记录.md`](../docs/springcloud-nacos-完整操作与调试记录.md) |

## 文件清单

```
shenyu-springcloud-demo/
├── pom.xml
├── README.md                                            ← 本文件
├── src/main/
│   ├── java/org/apache/shenyu/demo/springcloud/
│   │   ├── ShenYuSpringCloudDemoApplication.java      @SpringBootApplication + @EnableDiscoveryClient
│   │   ├── controller/OrderController.java            5 接口（4 官方 + 1 echo-body）
│   │   └── dto/OrderDTO.java                          id + name 的 POJO
│   └── resources/
│       ├── application.yml                            双通道配置（HTTP 注册 + Nacos discovery）
│       └── http/shenyu-springcloud-demo.http          IDEA HTTP Client 端到端用例（4 类对照 + 故障切换）
└── src/test/
    ├── java/org/apache/shenyu/demo/springcloud/
    │   ├── controller/OrderControllerTest.java        @WebMvcTest + MockMvc（6 用例）
    │   └── dto/OrderDTOTest.java                      POJO 测试（5 用例）
    └── resources/application.yml                      测试环境（关闭外部依赖）
```
