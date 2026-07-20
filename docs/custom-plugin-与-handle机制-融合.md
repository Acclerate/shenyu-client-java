# 融合理解：自定义插件开发 × 插件 handle 机制

> 融合两篇深度文档：
> - `custom-plugin-深度理解.md`（来源：https://shenyu.apache.org/zh/docs/developer/custom-plugin/ ，网关端"消费者"视角）
> - `plugin-handle-explanation-深度总结.md`（来源：https://shenyu.apache.org/zh/docs/user-guide/admin-usage/plugin-handle-explanation ，admin 端"生产者"视角）
> 整理时间：2026-07-17
> 标注：【页面原文】= 官方直接内容；【融合】= 两篇打通后的统一心智模型；【实证】= 本项目 `shenyu-client-java` 已验证结论

---

## 〇、两篇文档的关系：一枚硬币的两面

| 文档 | 视角 | 回答的问题 | 关键产出 |
|------|------|-----------|----------|
| custom-plugin | **网关端（bootstrap）消费者** | 插件怎么写？怎么进责任链？怎么读 handle？ | `ShenyuPlugin` / `AbstractShenyuPlugin` / `PluginDataHandler` |
| plugin-handle-explanation | **admin 端（生产者）** | handle 字段怎么声明？怎么动态出表单？怎么落库推送？ | 「插件处理管理」+ 三层 handle 模型 + websocket 生效链路 |

【融合】两篇文档在 **`handle` 这个契约**上汇合：

```
[生产者 admin]  声明字段 → 动态表单 → 写 handle JSON → 落库 → websocket 推送
                                                              │
                                                              ▼
[消费者 网关]  PluginDataHandler 订阅 → 插件链 → doExecute 读 rule.getHandle()
```

- **admin 决定 handle "有哪些字段、长什么样"**（元数据驱动动态表单）。
- **网关插件决定 handle "被怎么用"**（反序列化成配置对象去执行）。
- 两者必须**同 schema**，否则要么表单填不进去，要么插件读不到字段。

---

## 一、统一心智模型：三层 handle + 插件责任链 + 自定义插件

### 1.1 三层 handle（来自 plugin-handle-explanation）

| 层级 | 数据对象 | 作用范围 | 落库位置 |
|------|----------|----------|----------|
| 插件级 | `PluginData.handle` | 整个插件全局参数 | `shenyu_plugin.handle` |
| 选择器级 | `SelectorData.handle` | 命中该选择器的流量 | `shenyu_selector.handle` |
| 规则级 | `RuleData.handle` | 选择器内更细匹配（具体 path）的流量 | `shenyu_rule.handle` |

- handle 在 DB 里就是 **JSON 字符串**，分别存在三张表的 `handle` 列。
- 匹配顺序：请求 → 插件 → 选择器 → 规则；规则命中通常优先生效/细化选择器。
- 很多插件（如 divide）是"选择器级配负载均衡/超时，规则级逐接口细化"。

### 1.2 插件责任链（来自 custom-plugin）

- 网关按 `getOrder()` 升序穿过插件链：`Global → Sign → RateLimiter → … → 你的 CustomPlugin → Divide → …`。
- 匹配流量插件继承 `AbstractShenyuPlugin`：父类先做"插件启用→选择器→规则"匹配，命中后才回调子类 `doExecute(exchange, chain, selector, rule)`。
- `doExecute` 里通过 `rule.getHandle()` / `selector.getHandle()` / `pluginData` 拿到对应层级的 handle JSON。

【融合】**三层 handle 恰好对应 `doExecute` 能拿到的三类入参**：`pluginData`、`selector`、`rule` 各自携带自己那层的 handle。这就是"生产者声明三层、消费者按层读取"的精确对应。

---

## 二、端到端生命周期（生产→消费完整回路）

```
① 插件处理管理 声明字段        ② admin 动态表单 录入         ③ 客户端/后台 写 handle JSON
   (插件/选择器/规则 哪层           (按字段定义渲染表单项            (shenyu-client 注册 或 后台手填)
    要哪些字段、类型、必填)          下拉框字段查 shenyu_dict)         ↓
   ──────────────────────>  ──────────────────────>  落库 shenyu_plugin/selector/rule.handle
                                                                  │
                                            ┌─────────── 契约闸门 ───────────┐
                                            │ named() 同名 + handle schema 一致 │
                                            └─────────────────────────────────┘
                                                                  │ websocket 推送
                                                                  ▼
④ PluginDataHandler 订阅            ⑤ 插件链匹配                    ⑥ doExecute 读 rule.getHandle()
   handlerPlugin/Selector/Rule         插件启用→选择器→规则            反序列化 → 执行业务 → chain.execute
```

【实证】本项目已验证：admin 改动 handle 经 WebSocket 推网关通常**秒级生效**，无需重启；但 **Nacos discovery 上游变更需重启网关**触发 `syncData` 重新 watch（见 `nacos-discovery-配置深度分析.md`）。写自定义插件时也请注意这条"配置推送 vs 需重启"的边界。

---

## 三、契约的两个一致性（铁律）

要把两篇文档落地，必须满足两条一致性，缺一则整条链路断：

### 铁律 1：名字同名
```
admin 后台插件名  ==  插件 named()  ==  PluginDataHandler.pluginNamed()
```
- 三者任一端不一致，admin 配置推过去也找不到对应插件，`PluginDataHandler` 也不会被回调。
- 命名用 Camel Case（如 `divide`、`springCloud`、`shenYu`）。

### 铁律 2：handle schema 一致
```
「插件处理管理」声明的字段  ==  客户端/后台写的 JSON  ==  doExecute 反序列化目标类的字段
```
- admin 端声明了 `myField: string`，客户端/后台就必须写 `{"myField":"xxx"}`，插件 `doExecute` 反序列化目标类就必须有 `myField` 字段。
- 改 schema 必须三端同步；否则反序列化失败或字段为空。

【融合】`custom-plugin` 教你"消费者怎么读 handle"，`plugin-handle-explanation` 教你"生产者怎么声明 handle 字段让表单能填"——**铁律 2 是两者唯一的接缝**。

---

## 四、用一个自定义插件串起两端（实操模板）

假设要做一个自定义插件 `myPlugin`，规则级需要一个 `threshold`(数字) 字段：

**Step 1（生产者 / admin）** — 插件处理管理：
- 插件名选 `myPlugin`，字段 `threshold`，数据类型=数字，字段所属类型=**规则**，必填=是，默认值=100。
- 若数据类型选下拉框，须先在「字典管理」建 `threshold` 字典，否则选项为空。

**Step 2（生产者 / 客户端或后台）** — 写 handle：
- 客户端注册（如 `@ShenyuSpringMvcClient`）生成的规则 `handle = {"threshold":100,"loadBalance":"roundRobin"}`；
- 或后台在 myPlugin 插件下"添加规则"时，动态表单出现 `threshold` 输入框，填 100。

**Step 3（消费者 / 网关）** — 订阅 + 消费：
```java
// 1) 订阅：把 handle 缓存起来
@Component
public class MyPluginDataHandler implements PluginDataHandler {
    public String pluginNamed() { return "myPlugin"; }
    public void handlerRule(RuleData ruleData) {
        MyRuleHandle h = GsonUtils.getInstance().fromJson(ruleData.getHandle(), MyRuleHandle.class);
        CACHE.put(ruleData.getId(), h); // 避免每次请求解析 JSON
    }
}

// 2) 消费：doExecute 读取
public class MyPlugin extends AbstractShenyuPlugin {
    public String named() { return "myPlugin"; }
    public int getOrder() { return 50; }
    protected Mono<Void> doExecute(ServerWebExchange exchange, ShenyuPluginChain chain,
                                   SelectorData selector, RuleData rule) {
        MyRuleHandle h = GsonUtils.getInstance().fromJson(rule.getHandle(), MyRuleHandle.class);
        // 用 h.getThreshold() 执行业务
        return chain.execute(exchange);
    }
}
```
- 必须 `named()=="myPlugin"`、`pluginNamed()=="myPlugin"`（铁律 1）。
- `MyRuleHandle` 字段须与 Step1 声明、`handle` JSON 一致（铁律 2）。
- `doExecute` 末尾**必须** `chain.execute(exchange)`，否则请求卡链。

---

## 五、divide 实证对照（官方插件就是这么干的）

`divide` 插件是这两篇文档的最佳示范，本项目已端到端跑通：

| 环节 | divide 的做法 | 对应文档 |
|------|--------------|----------|
| 声明字段 | 插件处理管理为 divide 规则层声明 `loadBalance`/`timeout`/`retry` | plugin-handle-explanation |
| 写 handle | 客户端注册接口 → admin 规则 `handle={"loadBalance":"roundRobin","timeout":3000,...}` | plugin-handle-explanation |
| 同名 | `DividePlugin.named()` 返回 `"divide"` == admin 插件名 | custom-plugin |
| 订阅 | `DividePluginDataHandler` 缓存规则 handle + upstream | custom-plugin |
| 消费 | `DividePlugin.doExecute` 读 `rule.getHandle()` → 反序列化 `DivideRuleHandle` → 选上游转发 | custom-plugin |

【实证】本项目 `shenyu-http-demo`（contextPath `/http-demo11`）：客户端注册 → divide 插件下 选择器(`uri startsWith /http-demo11`) + 规则(`pathPattern /http-demo11/**`, `handle` 含 `loadBalance=roundRobin`) → `DividePlugin` 读 handle + Nacos discovery 喂 8179/8189 两实例，10 次请求完美轮询。

---

## 六、踩坑汇总（合并两篇）

1. **名字一致性铁律**：admin 插件名 == `named()` == `pluginNamed()`，任一端不一致配置落不到插件。
2. **handle schema 一致性**：声明字段 == 写的 JSON == 反序列化目标类，三端同步。
3. **下拉框字段必建字典**：否则表单下拉为空（plugin-handle-explanation）。
4. **三层 handle 模型**：插件级/选择器级/规则级，规则命中优先于选择器（plugin-handle-explanation）。
5. **勿漏 `chain.execute(exchange)`**：`execute`/`doExecute` 末尾必须放行（custom-plugin）。
6. **动态加载 vs Bean 二选一**：走 `extPlugin`/admin jar 上传就不要再 `@Component`，否则加载两次（custom-plugin）。
7. **配置推送 vs 重启边界**：handle 改动通常秒级生效；discovery 类上游变更需重启网关重新订阅（本项目实证）。
8. **WASM 文件名**：`x.y.z.MyShenyuWasmPlugin.wasm`，否则加载找不到（custom-plugin）。

---

## 七、一句话总结

`custom-plugin`（网关消费者）与 `plugin-handle-explanation`（admin 生产者）是同一套 `handle` 机制的两面：**admin 用「插件处理管理」把插件/选择器/规则的 handle 做成"元数据驱动的动态表单"并落库推送，网关插件在责任链的 `doExecute` 里把 handle 反序列化后消费**；把两端焊死的是两条契约——**同名(`named`) + 同 handle schema**。理解"三层 handle + 责任链 + 数据订阅生效链路 + 两条契约"，就同时掌握了自定义插件开发与整个 ShenYu 配置体系的主干。
