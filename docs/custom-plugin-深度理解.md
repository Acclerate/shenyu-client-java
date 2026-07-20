# 深度理解：ShenYu 自定义插件开发（custom-plugin）

> 来源页面：https://shenyu.apache.org/zh/docs/developer/custom-plugin/
> 文档版本：2.7.1（本工作区实际运行 admin/bootstrap 为 2.6.1，客户端为 2.7.0.1-jdk8，接口契约一致，少量配置名以 2.7.1 文档为准，运行时以 2.6.1 为准）
> 整理时间：2026-07-17
> 标注：【页面原文】= 官方文档直接给出的内容；【深度补充】= 结合 ShenYu 架构与本项目 `shenyu-client-java` 实证后补充的底层原理

---

## 〇、先定位：这篇文档讲的是什么，和本项目什么关系

`custom-plugin` 讲的是 **网关端（shenyu-bootstrap）的插件扩展能力**——即在数据面（网关）里新增一个"流量处理执行者"。
本项目 `shenyu-client-java` 是 **客户端（shenyu-client）**——它负责把后端服务的 API、选择器、规则、`handle` 配置**注册到 admin**，再由 admin 经数据同步通道（WebSocket）推送到网关，最终被网关里的插件消费。

两者通过一条**契约**衔接，这也是理解自定义插件的关键：

```
[你的后端服务] -- shenyu-client 注册 --> [shenyu-admin 落库] -- 数据同步 --> [shenyu-bootstrap 网关]
                                                                          |
                                                          插件 named() 必须 == admin 里的插件名
                                                          插件 doExecute() 读 rule.getHandle()（= 客户端注册的 JSON）
```

> 一句话：**插件定义"怎么处理流量"，客户端定义"对哪些流量、用什么参数处理"。** `named()` 是两边的接头暗号，`handle` JSON 是两边传递的参数。

---

## 一、插件是什么：网关的核心执行者

【页面原文】
- 插件是 `Apache ShenYu` 网关的核心执行者，每个插件在**开启**的情况下，都会对匹配的流量进行自己的处理。
- 插件分为两类：
  - **单一职责插件**：不能对流量进行自定义的筛选（纯功能性）。
  - **匹配流量处理插件**：能对匹配的流量，执行自己的职责调用链（有选择器/规则匹配）。

【深度补充】架构视角
- ShenYu 网关采用**责任链模式（Plugin Chain）**组织插件。一次请求按顺序穿过一串插件，每个插件决定"处理 / 跳过 / 放行"。
- 插件 ≠ 路由。`divide` 这类代理插件负责路由转发，但还有 `sign`（鉴权）、`rateLimiter`（限流）、`hystrix`/`sentinel`（熔断）、`rewrite`、`waf`、`jwt` 等治理类插件，它们在请求到达 `divide` 前后各司其职。
- 参考实现都在官方 `shenyu-plugin` 模块（`shenyu-plugin-divide`、`shenyu-plugin-sign` 等），自定义插件照猫画虎即可。

---

## 二、单一职责插件（`ShenyuPlugin` 接口）

### 2.1 引入依赖

```xml
<dependency>
    <groupId>org.apache.shenyu</groupId>
    <artifactId>shenyu-plugin-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2.2 直接实现 `org.apache.shenyu.plugin.api.ShenyuPlugin`

```java
public interface ShenyuPlugin {

    // 核心执行方法：在里面自由实现功能，最后 chain.execute(exchange) 放行
    Mono<Void> execute(ServerWebExchange exchange, ShenyuPluginChain chain);

    // 插件排序：决定在同类型插件链中的执行先后顺序
    int getOrder();

    // 插件名（Camel Case，如 dubbo、springCloud）。若继承 AbstractShenyuPlugin 则不用此方法
    default String named() { return ""; }

    // 是否跳过该插件（返回 true 则跳过）
    default Boolean skip(ServerWebExchange exchange) { return false; }
}
```

【深度补充】什么时候用单一职责插件？
- 你的逻辑**不依赖选择器/规则匹配**——例如：全局注入请求头、全局日志、跨切面埋点、WASM 包装等。
- 它没有 admin 后台"选择器/规则"概念，配置完全写死在代码或读插件级 `handle`。
- 注意：`execute()` 里**必须**在某个时机调用 `chain.execute(exchange)` 把请求继续往下传，否则请求会卡死在责任链里。

---

## 三、匹配流量处理插件（`AbstractShenyuPlugin`）

这是**最常用、最贴近本项目**的一种——它有完整的"选择器 + 规则"匹配能力，正好对应 shenyu-client 注册的元数据。

### 3.1 引入依赖

```xml
<dependency>
    <groupId>org.apache.shenyu</groupId>
    <artifactId>shenyu-plugin-base</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 3.2 继承 `AbstractShenyuPlugin`，实现 `doExecute`

```java
public class CustomPlugin extends AbstractShenyuPlugin {

    @Override
    public int getOrder() { return 0; }

    // 必须 == 你在 admin 后台基础配置 -> 插件管理 里新增的插件名称
    @Override
    public String named() { return "shenYu"; }

    @Override
    public Boolean skip(final ServerWebExchange exchange) { return false; }

    // 模板方法：选择器匹配到规则后，由父类回调到这里
    @Override
    protected Mono<Void> doExecute(ServerWebExchange exchange,
                                   ShenyuPluginChain chain,
                                   SelectorData selector,
                                   RuleData rule) {
        // rule.getHandle() 就是自定义 JSON 字符串（客户端/后台配置的）
        final String ruleHandle = rule.getHandle();
        final Test test = GsonUtils.getInstance().fromJson(ruleHandle, Test.class);
        // ... 你的业务逻辑 ...
        return chain.execute(exchange); // 继续责任链
    }
}
```

【深度补充】关键点（结合本项目实证）
1. **`named()` 必须 == admin 后台的插件名**。这是客户端/后台与网关插件的"接头暗号"。本项目 divide 插件能工作，正是因为客户端注册的 `contextPath` 在 admin 生成了名为 `divide` 的插件下的选择器/规则，且网关 `DividePlugin.named()` 返回 `"divide"`。自定义插件若名字对不上，admin 配置推过去也找不到对应插件。
2. **`rule.getHandle()` 是契约 JSON**。本项目 `shenyu-http-demo` 里，客户端注解注册的接口在 admin 落成的规则 `handle` 形如 `{"loadBalance":"roundRobin","timeout":3000,...}`，`DividePlugin` 反序列化成 `DivideRuleHandle` 来用。**自定义插件的 `handle` JSON schema，必须和客户端注册时写入的 `handle` 字符串格式一致**，否则反序列化失败或字段为空。
3. **选择器/规则匹配是父类统一做的**。你只需在 `doExecute` 里拿 `selector`、`rule` 干活，不用自己写匹配逻辑（这部分逻辑 `divide-plugin-验证手册.md` 已详拆）。
4. 配置流程：先在 admin 后台**新增插件**（名字与 `named()` 一致）→ 重新登录 admin → 在插件下新增选择器/规则 → 规则的 `handler` 字段填自定义 JSON。

---

## 四、插件执行链（核心心智模型）

【深度补充】把整条链路画清楚，比背接口更重要：

```
                         一次 HTTP 请求进入网关
                                 │
                                 ▼
        ┌──────────────── 插件责任链（按 getOrder 升序）────────────────┐
        │  GlobalPlugin(解析上下文) → SignPlugin → RateLimiterPlugin →  │
        │  HystrixPlugin → … → 【你的 CustomPlugin】→ DividePlugin → …  │
        └────────────────────────────────────────────────────────────┘
                                 │
                  CustomPlugin.execute() / doExecute()
                                 │
            ┌──────────── 父类先匹配 ────────────┐
            │ 1. 插件是否启用？ 否 → 跳过          │
            │ 2. 遍历选择器，筛选出命中选择器      │
            │ 3. 命中选择器下匹配规则              │
            │ 4. 命中 → 回调子类 doExecute()       │
            └────────────────────────────────────┘
                                 │
                读 rule.getHandle() 自定义 JSON，执行业务
                                 │
                    chain.execute(exchange) 放行到下游
                                 │
                                 ▼
                        DividePlugin 选上游节点并转发
```

- `getOrder()`：数值越小越先执行。治理类（鉴权/限流/熔断）排前面，代理类（divide）排后面。
- `skip(exchange)`：返回 `true` 则整个插件跳过（如某些插件的"仅对特定请求生效"）。
- **`doExecute` 末尾必须 `return chain.execute(exchange)`**，否则请求不出链。

---

## 五、订阅插件数据变化：`PluginDataHandler`

【页面原文】新增一个类实现 `org.apache.shenyu.plugin.base.handler.PluginDataHandler`：

```java
public interface PluginDataHandler {
    default void handlerPlugin(PluginData pluginData) {}   // 插件配置变更
    default void removePlugin(PluginData pluginData) {}
    default void handlerSelector(SelectorData selectorData) {} // 选择器变更
    default void removeSelector(SelectorData selectorData) {}
    default void handlerRule(RuleData ruleData) {}          // 规则变更
    default void removeRule(RuleData ruleData) {}
    String pluginNamed();  // 必须 == 你的插件名
}
```

【深度补充】这是"客户端/后台配置 → 网关内存"的落地钩子
- shenyu-client 注册、或 admin 后台改动，都会经数据同步通道推送，`PluginDataHandler` 的对应方法被回调。
- **`pluginNamed()` 必须 == 插件 `named()`**（三者一致：admin 插件名 = 插件 `named()` = `pluginNamed()`）。
- 典型用法：在 `handlerRule` 里把 `rule.getHandle()` 反序列化成缓存对象，插件 `doExecute` 直接读缓存，避免每次请求都解析 JSON。
- 注册：同插件一样，`@Bean` 或 `@Component`。

> 与本项目的关系：本项目已验证——admin 改动 `handle` 经 WebSocket 推网关通常秒级生效；但 **Nacos discovery 上游变更需重启网关**触发 `syncData` 重新 watch（见 `nacos-discovery-配置深度分析.md`）。写自定义插件时也请注意这种"配置推送 vs 需要重启才能重新订阅"的边界。

---

## 六、注册方式

两种方式任选其一，把插件/处理器变成 Spring Bean：

```java
@Bean
public ShenyuPlugin customPlugin() { return new CustomPlugin(); }

@Bean
public PluginDataHandler pluginDataHandler() { return new PluginDataHandler(); }
```

或直接在实现类上加 `@Component`。

---

## 七、动态加载 & 插件 jar 上传（生产部署）

【页面原文】
- 用动态加载时，扩展的 `ShenyuPlugin` / `PluginDataHandler` **不用**成为 Spring Bean，只需打成 jar 放到指定目录。
- 网关配置：

```yaml
shenyu:
  extPlugin:
    path: // 加载扩展插件 jar 包路径
    enabled: true      # 是否开启
    threads: 1         # 加载插件线程数
    scheduleTime: 300  # 轮询间隔（秒）
    scheduleDelay: 30  # 网关启动后延迟加载（秒）
```

**加载路径优先级**：`-Dplugin-ext=xxxx` ＞ `shenyu.extPlugin.path` ＞ 默认 `ext-lib`（网关启动目录下）。

**插件 jar 上传到 admin（热加载）**：
- admin 基础配置 → 插件管理 → 新增插件，在 `pluginJar` 上传自定义 jar。
- 支持热加载；在线改 jar 需提升版本号（如 `1.0.1` → `1.0.2`）。
- 自定义插件若依赖第三方包，需把这些 jar 加到 `shenyu-bootstrap` 启动的 `-cp`。

【深度补充】动态加载 vs Spring Bean 二选一
- 简单场景（自己能控制 bootstrap 依赖）：直接 `@Component` 最省事。
- 插件要**独立交付、热更新、不重打包网关**：走 `extPlugin` / admin jar 上传。注意此时**不要**再 `@Component`，否则会被加载两次。

---

## 八、多语言 / WASM 扩展（概览）

【页面原文】除 Java 外，可用支持 WASM 的语言（如 Rust）写插件逻辑：
- 单一职责：`shenyu-plugin-wasm-api` + 继承 `AbstractWasmPlugin`，实现 `doExecute(exchange, chain, argumentId)` / `getArgumentId` / `initWasmCallJavaFunc`。
- 匹配流量：`shenyu-plugin-wasm-base`，Rust 侧需实现 `doExecute`（`before`/`after` 可选）。
- 构建：`cargo build --target wasm32-wasi --release`，wasm 文件名须为 `x.y.z.MyShenyuWasmPlugin.wasm`，放到插件模块 `resources`。

> 适用：想用 Rust/Go 等写高性能插件逻辑，又不想重打包 Java。本项目暂未涉及，了解即可。

---

## 九、与本项目的连接：从客户端"驱动"一个自定义插件

如果你在本工作区（`shenyu-client-java`）开发，想让客户端注册的数据被你的自定义插件消费，需要**三端对齐**：

| 维度 | 客户端（本项目） | admin 后台 | 网关插件 |
|------|------------------|-----------|----------|
| 插件名 | 注册时声明的 plugin 名 | 插件管理新增同名插件 | `named()` / `pluginNamed()` 返回同名 |
| 匹配流量 | `@ShenyuSpringMvcClient` 注册接口 → 生成选择器(`uri startsWith` contextPath) + 规则(`pathPattern`) | 选择器/规则落库 | 父类自动匹配选择器/规则 |
| 参数(handle) | 客户端注册时写入的 `handle` JSON 字符串 | 存 `shenyu_rule.handle` | `doExecute` 里 `rule.getHandle()` 反序列化 |

**实证锚点（本项目已跑通）**：`shenyu-http-demo`（contextPath `/http-demo11`）经客户端注册 → admin 生成 divide 插件下的 选择器(`uri startsWith /http-demo11`) + 规则(`pathPattern /http-demo11/**`, `handle` 含 `loadBalance=roundRobin`) → 网关 `DividePlugin` 读 handle + Nacos discovery 喂的 8179/8189 两实例，10 次请求完美轮询。

**推论**：自定义插件要能被本客户端驱动，光写网关插件不够，还要让客户端知道往哪个插件名下注册、用何种 `handle` schema。标准客户端只内置了对 `divide`/`spring-cloud`/`dubbo` 等官方插件的注册 model；自定义插件通常需扩展客户端注册逻辑 + admin 端建同名插件 + 在「插件处理管理」声明 handle 字段（见 `plugin-handle-explanation-深度总结.md` 的三层 handle 模型）。

---

## 十、开发清单 & 踩坑提示

1. **名字一致性铁律**：admin 插件名 == 插件 `named()` == `pluginNamed()`。任何一端不一致，配置推过去也落不到插件。
2. **`handle` JSON 契约**：客户端的 `handle` 字符串格式必须 == 插件 `doExecute` 反序列化目标类的字段。改 schema 要两端同步。
3. **三层 handle 模型**：插件级 / 选择器级 / 规则级（详见 `plugin-handle-explanation-深度总结.md`）。自定义字段用「插件处理管理」声明，下拉框字段需先建字典。
4. **不要漏 `chain.execute(exchange)`**：`execute`/`doExecute` 末尾必须放行，否则请求卡链。
5. **动态加载 vs Bean 二选一**：走 `extPlugin`/admin jar 上传就不要再 `@Component`。
6. **WASM 文件名**：`x.y.z.MyShenyuWasmPlugin.wasm`，否则加载找不到。
7. **配置推送 vs 重启边界**：多数 handle 改动秒级生效；discovery 类上游变更需重启网关重新订阅（本项目实证）。

---

## 十一、一句话总结

`custom-plugin` 文档讲透一件事：**ShenYu 用"实现 `ShenyuPlugin`/`AbstractShenyuPlugin` + 注册成 Spring Bean + `PluginDataHandler` 订阅数据"的方式，让你在网关责任链里插入自己的流量处理逻辑**。而插件要真正"被本项目的客户端驱动"，靠的是三方契约——**同名(`named`) + 同 handle schema**——客户端注册的选择器/规则/handle 数据，最终在网关插件的 `doExecute` 里被消费。理解"责任链 + 选择器/规则匹配 + handle JSON 契约 + 数据订阅生效链路"，就掌握了自定义插件乃至整个 ShenYu 插件体系的骨架。
