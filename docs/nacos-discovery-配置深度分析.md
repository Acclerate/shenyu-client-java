# 深度分析：shenyu-http-demo 配置是否满足「Nacos 注册 + divide Discovery Config 通过 Type 发现」

> 分析对象：`shenyu-http-demo/pom.xml` + `shenyu-http-demo/src/main/resources/application.yml`
> 目标链路：① 客户端把实例注册到 Nacos；② divide 插件通过 **Selector 的 Discovery Config 把 Type 设为 nacos**，从而发现①里注册的服务实例作为上游。
> 方法：联网核对官方 Discovery 文档 + 精读仓库源码（client 2.7.0.1-jdk8 + registry-nacos 2.6.1）+ 实测环境证据。

---

## 0. 一句话结论

- **① 注册到 Nacos：✅ 满足。** 依赖拼装与开关配置正确，实例已注册到 Nacos 服务 `shenyu-http-demo`（实测 2 个健康实例）。
- **② divide 通过 Type 发现已注册服务：⚠️ 当前 yml 存在命名缺陷，仅靠配置"不自动满足"，取决于 admin 侧如何配置。**
  - 走「**admin UI 手动**建 Selector 的 Discovery Config，且 listenerNode 填 `shenyu-http-demo`」→ ✅ 成功。
  - 走「**客户端自动推送** discovery 配置」→ ❌ 必然失败（源码证明 listenerNode 被设成 `registerPath`=`/shenyu/register/http-demo`，与该服务名不匹配）。

**根因**：ShenYu 设计中 `registerPath` 即是"要监听的 Nacos 服务名"（官方原文 *"registerPath can be understood as the name of the service to be monitored"*），而当前 yml 把 `registerPath`(`/shenyu/register/http-demo`) 与 `props.name`(`shenyu-http-demo`) 设成了两个不同的值，导致"注册的服务名"与"admin 监听节点"对不上。

---

## 1. 源码级证据链（为什么必然如此）

| 环节 | 实际取值 | 源码依据 |
|------|----------|----------|
| ① 客户端注册进 Nacos 的 **serviceName** | `shenyu-http-demo` | `NacosInstanceRegisterRepository.persistInstance`：`namingService.registerInstance(instance.getAppName(), groupName, inst)` |
| `appName` 的来源 | `props.name` = `shenyu-http-demo` | `InstanceRegisterListener.onApplicationEvent` 第 87 行：`instance.setAppName(discoveryConfig.getProps().getProperty("name"))` |
| ② 客户端推给 admin 的 **listenerNode** | `/shenyu/register/http-demo` | `ClientDiscoveryConfigRefreshedEventListener.buildDiscoveryConfigRegisterDTO` 第 92 行：`.listenerNode(shenyuDiscoveryConfig.getRegisterPath())` |
| admin 订阅 Nacos 用的服务名 | = listenerNode | admin 的 `NacosDiscoveryService` 以 `discovery_handler.listener_node` 作为 Nacos `serviceName` 订阅 |

**结论**：admin 端若按客户端推送来订阅，会去监听 Nacos 服务 `/shenyu/register/http-demo`，而该服务下 **没有任何实例**（实例都在 `shenyu-http-demo` 下）→ 上游为空。两者必须相等。

> 旁证：实测数据库 `shenyu_261` 中 `/http-demo11` 选择器 **没有任何 `discovery_rel` 绑定**，说明客户端这次推送的 discovery 配置在 admin 2.6.1 下**根本没有成功落地 nacos 发现对象**——与"客户端驱动路径不可靠"的推断一致。

---

## 2. pom.xml 分析（注册侧是否正确）

```xml
<shenyu.version>2.7.0.1-jdk8-SNAPSHOT</shenyu.version>      <!-- client starter 版本 -->
<dependency> shenyu-spring-boot-starter-client-springmvc   <!-- 带来 @ShenyuSpringMvcClient + http register + discovery 自动配置 -->
<dependency> shenyu-registry-nacos:2.6.1
    <exclusions>
      shenyu-registry-api        <!-- 排除，用本仓库 2.7.0.1 jdk8 的 7 方法接口 -->
      nacos-client               <!-- 排除，下面单独锁 2.2.4 -->
<dependency> shenyu-common:2.6.1   <!-- 提供 ShenyuException 等 POJO，Java8 字节码 -->
<dependency> nacos-client:2.2.4    <!-- 兼容 Nacos 2.3.2 的 gRPC（2.0.4 会握手失败） -->
```

判定：**注册侧依赖拼装正确、可运行**，理由：
1. `shenyu-registry-nacos:2.6.1` 是 Java8 字节码（2.7.x 是 Java17，会抛 `UnsupportedClassVersionError`），选 2.6.1 合理。
2. 排除自带的 `nacos-client:2.0.4` 并锁 `2.2.4`，规避了与 Nacos 2.3.2 的 gRPC 握手问题（鉴权关闭后该风险已解除，但 2.2.4 仍为稳妥选择）。
3. 排除 `shenyu-registry-api` 复用本仓库编译出的 jdk8 接口，解决版本错配。

⚠️ **风险（非阻塞）**：client starter 2.7.0.1 与 registry-nacos 2.6.1 / common 2.6.1 是**非标准跨版本混用**。当前已实测 2 实例注册成功，功能 OK；但属"能跑"而非"官方支持组合"，长期维护需注意。

---

## 3. application.yml 逐段核对

```yaml
shenyu:
  register:
    registerType: http
    serverLists: http://localhost:9096          # 宿主机访问 admin（客户端在宿主），正确
    props: {username: admin, password: 1qaz!QAZ}
  client:
    http:
      props:
        appName: http-demo
        contextPath: /http-demo11                # 选择器名/contextPath
        host: "10.19.236.*"                       # 多网卡时锁定网段，正确
  discovery:
    enable: true                                 # ✅ 建 ShenyuDiscoveryConfig bean
    serverList: localhost:8848                   # 客户端自己连 Nacos 用，宿主映射端口，正确
    register: true                               # ✅ 建 InstanceRegisterListener 真正注册实例
    type: nacos                                 # ✅ SPI key -> nacos 实现
    protocol: http://
    registerPath: /shenyu/register/http-demo     # ⚠️ 见 §4：被推为 admin listenerNode，≠ props.name
    props:
      name: shenyu-http-demo                     # ✅ 实例注册进 Nacos 的服务名（已实测生效）
      nacosNameSpace: ""                         # ✅ public（空字符串，非 "public"）
      groupName: DEFAULT_GROUP                   # ✅ 与 Nacos 实际分组一致
      username: nacos                            # 鉴权已关，可不填，但无害
      password: nacos
```

### 3.1 注册侧（①）：全部满足 ✅
- `enable`+`register`+`type:nacos` 三开关齐全 → `InstanceRegisterListener` 会注册实例。
- `props.name` → 实例进 Nacos 服务 `shenyu-http-demo`，`groupName`/`nacosNameSpace` 与 Nacos 实际一致。
- 实测：Nacos `DEFAULT_GROUP@@shenyu-http-demo` 有 8179、8189 两个健康实例。

### 3.2 发现侧（②）：命名不一致 ⚠️
- 客户端推送的 `listenerNode = registerPath = /shenyu/register/http-demo`，而实例服务名是 `shenyu-http-demo`。
- 二者不等 → 客户端驱动的发现路径必然找不到实例。

### 3.3 关于 username/password
- Nacos `NACOS_AUTH_ENABLE=false`，`NacosInstanceRegisterRepository.init` 用 `getProperty(USERNAME, "")` 默认空 → 不填也能连。yml 里填了 `nacos/nacos` 无害（关鉴权时被忽略）。这是上一轮已确认的小优化点。

### 3.4 serverList 地址语义
- yml 里 `discovery.serverList: localhost:8848` 是**客户端自己连 Nacos** 的地址（客户端在宿主机，指向宿主映射端口），正确。
- **注意区分**：admin 在容器内，admin 侧 Discovery Config 的 serverList 必须填容器地址 `nacos_server_232:8848`（不是 localhost）。这由 admin 端配置决定，本文档分析的 pom/yml 不控制 admin 侧。

---

## 4. 核心缺陷详解：registerPath 必须 = 实例服务名

官方 Nacos 示例（2.6.x / 2.7.0.2 文档一致）：
```yaml
shenyu:
  discovery:
    enable: true
    protocol: http://
    type: nacos
    serverList: ${nacos.host}:${nacos.port}
    registerPath: shenyu_discovery_demo_http_common   # ← 这就是要监听的 Nacos 服务名
    props:
      groupName: SHENYU_GROUP
```
文档原文：*"registerPath can also be understood as the name of the service to be monitored."*（registerPath 同样可理解为需要监听的服务的名称）。

即 ShenYu 的**设计意图**是 `registerPath` 充当"Nacos 服务名"这一角色，由 `InstanceRegisterListener` 注册实例、`ClientDiscoveryConfigRefreshedEventListener` 把同一 `registerPath` 推给 admin 做监听节点——两者天然相等，闭环自洽。

当前 yml 却额外设了 `props.name: shenyu-http-demo`，且 `registerPath` 取了另一个值 `/shenyu/register/http-demo`：
- 注册实例时用的服务名 = `props.name` = `shenyu-http-demo`（源码用 `appName`）
- 推给 admin 的监听节点 = `registerPath` = `/shenyu/register/http-demo`

→ 注册与监听指向了两个不同的 Nacos 服务名 → **断链**。

> 补充：即便不写 `props.name`，`InstanceRegisterListener` 会把它默认成 `spring.application.name`（`shenyu-http-demo`）。所以无论怎样，注册服务名都来自 `props.name`/`appName`，**绝不会**自动等于 `registerPath`——除非你显式把 `registerPath` 也设成同一个名字。

---

## 5. 修复方案（若要保留客户端驱动路径）

**方案 A（推荐，最小改动）**：让 `registerPath` 等于实例服务名。
```yaml
shenyu:
  discovery:
    registerPath: shenyu-http-demo   # 与 props.name 保持一致
```
改后：客户端推送的 listenerNode = `shenyu-http-demo` = 实例注册的服务名 → admin 能发现 2 实例，客户端驱动路径自洽。

**方案 B**：删掉 `props.name`，让 appName 默认 = `spring.application.name` = `shenyu-http-demo`，同时 `registerPath` 也设 `shenyu-http-demo`（或干脆 `registerPath` 留成和 appName 相同语义）。但**实测证明 2.6.1 下客户端推送的 discovery 配置并不会可靠落地**（DB 无 discovery_rel），故即使改对，仍建议配合 admin UI 手动核对。

**方案 C（本环境最稳，原改造计划主推）**：不依赖客户端推送，**在 admin UI 手动**为 `/http-demo11` 选择器建 Discovery Config：
- 类型：`nacos`
- 监听节点(listenerNode)：`shenyu-http-demo`（手动写对，绕开 registerPath 的坑）
- 服务器URL：`nacos_server_232:8848`（容器地址）
- 参数：`groupName=DEFAULT_GROUP`、`nacosNameSpace=`（public，留空）
- 见 `docs/nacos-discovery-divide-改造计划.md` 阶段 C1。

---

## 6. 对原改造计划的影响

1. **强化"必须用 C1（admin UI 手动），C2（客户端驱动）不可靠"的结论**：此前仅观测到"C2 没生成 nacos 对象"，现已有源码级根因（registerPath≠props.name + 2.6.1 推送未落地）。
2. **C1 的 listenerNode 必须填 `shenyu-http-demo`**（= 实例实际服务名），不能填 `registerPath` 的值。这点原计划已正确设定。
3. **若你坚持走 C2**，务必先把 `registerPath` 改成 `shenyu-http-demo`（§5 方案 A），否则一定失败。
4. 注册侧（pom + `props.name`/`groupName`/`nacosNameSpace`）**无需改动**，已正确。

---

## 7. 最终判定

| 需求 | 是否满足 | 说明 |
|------|----------|------|
| Nacos 服务注册 | ✅ 满足 | pom 依赖拼装正确 + 三开关齐全 + `props.name` 生效，实测 2 实例在线 |
| divide Discovery Config 通过 Type=nacos 发现该服务 | ⚠️ 配置本身不直接满足 | 取决于 admin 侧配置：手动 C1 填对 `shenyu-http-demo` 即成功；客户端驱动 C2 因 `registerPath≠props.name` 必然失败 |
| 是否需要改 pom/yml 才能"注册" | 否 | 注册已 OK |
| 是否需要改 yml 才能"客户端自动发现" | 是 | 须把 `registerPath` 改为 `shenyu-http-demo`（方案 A），但仍建议配合 admin 手动核对 |
