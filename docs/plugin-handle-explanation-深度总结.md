# 深度理解：ShenYu「插件配置 / 插件处理(handle)说明」

> 来源页面：https://shenyu.apache.org/zh/docs/user-guide/admin-usage/plugin-handle-explanation
> 文档版本：2.7.1
> 整理时间：2026-07-17
>
> 标注说明：
> - 【页面原文】= 页面直接给出的内容
> - 【深度补充】= 页面未写、但理解该机制必须的底层原理（结合 ShenYu 架构与本项目 shenyu-client-java 实证）

---

## 一、页面定位（【页面原文】）

本页属于 shenyu-admin 后台使用文档，讲两件事：
1. **插件管理**：插件的统一启停与插件级全局配置。
2. **插件处理管理（Plugin Handle Management）**：如何给「插件 / 选择器 / 规则」动态添加 `handle` 字段。

前置条件：先启动 `shenyu-admin`（如本地部署），访问 `http://localhost:9095`，默认账号 `admin / 123456`。

---

## 二、核心心智模型：什么是 handle，为什么分三层（【深度补充】）

ShenYu 网关是一个**插件链（plugin chain）**：每个请求按顺序经过一串插件，插件对流量做处理、路由、限流、鉴权等。

每个插件要"工作"就需要配置，而这些配置按**作用范围**分三级：

| 层级 | 数据对象 | 作用范围 | 典型内容 |
|------|----------|----------|----------|
| 插件级 | `PluginData.handle` | 整个插件生效的全局参数 | Dubbo 注册中心地址、rateLimiter 算法类型 |
| 选择器级 | `SelectorData.handle` | 命中该选择器的那部分流量 | divide 的负载均衡/超时/重试、springCloud 的 path |
| 规则级 | `RuleData.handle` | 选择器内更细匹配（具体 path 模式）的流量 | 单个接口的负载均衡/超时、rewrite 的替换串 |

- **handle 在数据库里就是一个 JSON 字符串**，分别存在 `shenyu_plugin` / `shenyu_selector` / `shenyu_rule` 表的 `handle` 列。
- 网关（bootstrap）通过 websocket 订阅后，把这些 JSON 反序列化成插件特定的配置对象，运行时读取。
- **匹配顺序**：请求 → 匹配插件 → 匹配选择器 → 匹配规则。规则命中后，规则级 handle 通常优先生效/细化选择器级 handle。很多插件（如 divide）是"选择器级配负载均衡与超时，规则级再逐接口细化"。

---

## 三、插件管理（【页面原文】+【深度补充】）

- 在「插件管理」里可**统一开启 / 关闭**每个插件。
- 某些插件需要在此配置**插件级信息**，例如给 `Dubbo` 插件设置**注册中心**。
- 这也是 divide、springCloud、sentinel、ratelimiter 等插件的"总开关 + 全局参数"入口。
- 【深度补充】插件级 handle 关闭时，整条链跳过该插件；开启但无选择器/规则命中，则该插件对流量不做具体处理（仅可能执行插件级逻辑）。

---

## 四、插件处理管理 —— 本页核心（【页面原文】）

这是页面的创新点：**用元数据驱动动态表单**，而非把每个插件的字段写死在前端。

传统做法：插件 handle 字段硬编码在代码/前端里。
ShenYu 做法：管理员在「插件处理管理」声明"某插件在 插件/选择器/规则 哪一层 需要哪些字段"，前端据此**动态渲染表单**。

### 新增 / 编辑一个 handle 字段时需填写的 11 个属性（页面原文逐条）

1. **插件名**：作用于哪个插件（下拉选择）。
2. **字段**：字段名称（如 `path`、`timeout`）。
3. **描述**：字段描述信息。
4. **数据类型**：数字 / 字符串 / 下拉框。
   - 选「下拉框」时，表单里的下拉选项来自**字典表**——需提前在「字典管理」录入信息（按字段名去 `shenyu_dict` 查出所有可选项）。
5. **字段所属类型**：插件 / 选择器 / 规则（决定该字段出现在哪一级表单）。
6. **排序**：字段在表单中的顺序。
7. **是否必填**：是 / 否。
8. **默认值**：为该字段指定默认值。
9. **输入提示**：用户填写时出现的提示信息。
10. **校验规则(正则)**：用户填写时用正则校验。

### 页面示例（原文）
给 `springCloud` 插件的**规则**层新增：
- 字符串字段 `path`
- 数字字段 `timeout`

之后在 `插件列表 → rpc proxy → springCloud → 添加规则` 时，即可填写 `path`、`timeout` 两个字段。

---

## 五、深层机制（【深度补充】，理解页面才"深度"）

1. **存储分离**
   - 字段定义（元数据）存在 `shenyu_plugin_handle` 表。
   - 字段实际值存在 `shenyu_plugin.handle` / `shenyu_selector.handle` / `shenyu_rule.handle`。
2. **表单渲染**
   - admin 前端读 `shenyu_plugin_handle`，按「字段所属类型 + 排序」动态出表单项。
   - 数据类型=下拉框 → 查 `shenyu_dict` 中 `type = 字段名` 的记录作为可选项；所以**下拉框字段必须先建字典，否则选项为空**。
3. **生效链路**
   admin 改动 → websocket 推送给 bootstrap 网关 → `PluginDataHandler` / `SelectorDataHandler` / `RuleDataHandler` 解析 handle JSON → 插件运行时读取生效（**无需重启网关**）。
4. **覆盖关系（再次强调）**
   规则命中优先于选择器；选择器级配"大方向"，规则级配"具体接口"。
5. **与 Discovery 的关系（结合本项目实证）**
   divide 这类插件的上游服务发现，正是在「选择器/规则 handle + discovery 配置」协作下完成的。本工作区已验证：divide 需配 选择器(`uri startsWith` contextPath) + 规则(`pathPattern /**` + `loadBalance`)，并由 Nacos discovery 喂上游实例（serverList 用无下划线别名 `nacos:8848`），端到端才通。

---

## 六、各插件 handle 字段速查（【深度补充】，本页未枚举）

> 以下为 ShenYu 通用知识整理，便于把"动态字段机制"落到具体插件。**页面本身并未列出各插件字段**，此处供扩展参考。

| 插件 | 插件级 handle 常见项 | 选择器级 handle 常见项 | 规则级 handle 常见项 |
|------|----------------------|------------------------|----------------------|
| divide | （一般空） | `loadBalance`、`timeout`、`retry`、`header`、`fallbackStatusCode` | `loadBalance`、`timeout`、`retry` |
| springCloud | 注册中心相关 | — | `path`、`timeout`（即页面示例） |
| dubbo | 注册中心 | — | `timeout`、`retries`、`group`、`version` |
| grpc | — | — | `timeout` |
| websocket | — | — | `protocol`/`path` |
| sentinel | flow 规则模板 | 流控资源 | `grade`、`count`、`strategy` |
| hystrix | — | — | `commandKey`、`timeout`、熔断 `fallback` |
| resilience4j | — | — | `timeout`、`circuitBreaker` 配置 |
| ratelimiter | `algorithm`（令牌桶/漏桶） | — | `replenishRate`、`burstCapacity`、`requestedTokens` |
| rewrite | — | — | `regex`、`replace` |
| context-path | — | — | `contextPath` |
| sign | 签名开关/密钥 | — | 签名算法/密钥引用 |
| modify-response | — | — | 改写规则 |
| waf | — | — | 黑/白名单规则 |
| jwt / oauth2 | `secret`/`publicKey` | — | 鉴权范围 |
| param-mapping | — | — | 参数映射规则 |
| cryptor | 加密算法 | — | 加密字段列表 |

> 提示：上表字段是否需要、叫什么，最终以**插件处理管理里的定义**和**插件源码**为准；页面讲的是"如何定义字段"的机制，而非"每个插件有哪些字段"的字典。

---

## 七、实践要点（【深度补充】+ 本项目关联）

- **加扩展字段不改代码**：想给某插件/选择器/规则加自定义字段，用「插件处理管理」声明即可，前端自动出表单。
- **下拉框字段必建字典**：否则新建/编辑时下拉为空。
- **改完 handle 定义后**：去对应插件的「添加选择器 / 添加规则」即可看到新字段。
- **动态生效**：改 handle 经 websocket 推网关，通常无需重启（但本项目实证：Nacos discovery 上游变更需**重启网关**触发 `syncData` 重新 watch）。
- **本项目落地实例**：shenyu-client-java 的 `shenyu-http-demo`（divide + Nacos discovery）正是这套"插件/选择器/规则三层 handle + discovery"模型的端到端实例——选择器配 `uri startsWith /http-demo11`，规则配 `pathPattern /http-demo11/**` + `loadBalance=roundRobin`，由 Nacos discovery 喂 8179/8189 两实例，10 次请求完美轮询。

---

## 八、一句话总结

本页讲透一件事：**ShenYu 用「插件处理管理」把插件/选择器/规则的 handle 配置做成"元数据驱动的动态表单"**——你先声明字段（名称、类型、层级、必填、默认值、正则校验，下拉框还需配字典），admin 前端据此动态渲染录入界面，存成 JSON 落库，再经 websocket 推送到网关生效。理解 handle 的"三层模型 + 存储分离 + 动态渲染 + websocket 生效链路"，就掌握了整个 ShenYu 配置体系的主干。
