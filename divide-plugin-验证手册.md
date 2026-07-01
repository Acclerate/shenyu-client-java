# ShenYu 2.6.1 divide 插件功能验证手册

## 环境

| 组件 | 地址 | 版本 |
|------|------|------|
| shenyu-admin (Docker) | `localhost:9096` | 2.6.1 |
| shenyu-bootstrap (Docker) | `localhost:9196` | 2.6.1 |
| demo (IDEA 多实例) | `10.19.236.150:8179` / `:8189` | Spring Boot 2.7.18 |
| demo contextPath | `/http-demo11` | — |



# Apache ShenYu Divide 插件深度解析
Divide 插件是 Apache ShenYu 网关体系中**最核心、最基础的 HTTP 反向代理插件**，是所有 HTTP 流量转发的基石，对应传统网关的「路由转发 + 负载均衡」核心能力，同时承载了流量治理、超时管控、故障重试等进阶能力。以下从入门认知、核心功能、底层原理、生产实践四个层级逐层拆解。

## 一、入门层：核心定位与架构位置
### 1. 插件定位
Divide 插件是专门用于处理 **HTTP 协议请求**的代理插件，是 ShenYu 网关数据面的「流量分发执行器」。所有 HTTP 请求经过鉴权、限流、熔断等前置治理插件后，最终都会进入 Divide 插件完成路由选择与转发参数组装，再交由底层 HTTP 客户端发往后端服务。

### 2. 在插件链中的位置
ShenYu 采用责任链模式组织插件，Divide 属于**代理类末端插件**：
- 前置插件：GlobalPlugin（全局上下文解析）、SignPlugin（鉴权）、RateLimiterPlugin（限流）、HystrixPlugin（熔断）等治理类插件
- 核心位置：Divide 插件负责匹配路由规则、选定上游节点、设置转发参数
- 后置执行：由底层响应式 HTTP 客户端（基于 Spring WebFlux WebClient）完成真正的请求转发与响应回流

### 3. 核心价值
对比 Nginx 等传统反向代理，Divide 插件的核心优势是**全动态化、插件化、细粒度管控**：
- 所有路由规则、上游节点、治理策略均可通过 Admin 后台热更新，无需重启网关
- 支持选择器+规则两级匹配，可实现服务级、接口级的精细化流量治理
- 与服务发现体系深度打通，上游服务上下线自动感知，适配微服务动态架构

## 二、进阶层：四大核心功能深度拆解
对应官方文档 1.3 节的插件功能，以下逐点展开配置逻辑、设计思路与适用场景。

### 1. 多维度流量治理能力
#### 能力本质
基于「选择器 + 规则」两级配置模型，支持通过 URI、Header、Query 参数等请求属性进行条件匹配，实现流量的精细化拆分。
- **选择器**：粗粒度匹配，通常对应一个服务集群（如 `/http` 前缀的所有请求），可配置服务级别的上游节点列表
- **规则**：细粒度匹配，通常对应单个或一组接口（如 `/http/order`），可配置接口级别的负载均衡、超时、重试策略

#### 典型落地场景
- **灰度发布**：通过请求 Header 中的用户标签、版本号，将指定比例/范围的流量导向新版本服务集群
- **A/B 测试**：按用户 ID 哈希、Query 参数等维度，将流量切分到不同逻辑版本的服务
- **多环境路由**：根据请求头中的环境标识，将测试、预发流量转发到对应环境的服务集群

### 2. 带预热机制的负载均衡体系
Divide 插件内置三种负载均衡策略，同时支持服务预热机制，保障节点上线的稳定性。

#### 三种负载均衡策略
| 策略 | 实现原理 | 适用场景 |
|------|----------|----------|
| 加权轮询（roundRobin） | 按权重比例依次分配请求，流量分配平滑均匀 | 后端节点配置差异小、追求流量均匀分布的常规场景 |
| 加权随机（random） | 按权重随机分配节点，大流量下趋近权重比例 | 实现简单、性能开销小的通用场景 |
| 一致性哈希（hash） | 基于请求 IP 计算哈希，带虚拟节点优化，同 IP 请求固定落到同一节点 | 需要会话保持、本地缓存的业务场景；节点增减时流量迁移更平滑 |

#### 服务预热机制（核心设计亮点）
- **设计初衷**：刚启动的服务需要完成 JVM 预热、缓存加载、资源初始化等过程，直接承接全量流量易引发响应超时甚至宕机。预热机制通过「线性提升权重」的方式，让新节点软启动。
- **权重计算逻辑**：实际权重 = 配置权重 ×（服务已运行时间 / 预热总时长），预热期内权重随时间线性增长，预热结束后达到配置的满权重。
  > 示例：配置权重 50，预热时间 100ms，服务启动 50ms 时，实际生效权重为 25。
- **默认值**：服务自动注册时，默认权重为 50，默认预热时间为 10ms，生产环境可根据服务启动速度按需调大。

### 3. 接口级流量与超时管控
所有参数均为**规则级别**，即每个接口可独立配置，灵活性远高于全局配置。
- `headerMaxSize`：请求头最大大小限制，可防御超大请求头攻击，也可适配特殊业务的大 Header 场景
- `requestMaxSize`：请求体最大大小限制，普通接口保持较小阈值提升安全性，文件上传等接口单独放大阈值
- `timeout`：上游请求超时时间，核心接口可设置更短超时实现快速失败，慢业务接口可适当延长

### 4. 可配置的超时重试机制
从 2.4.3 版本开始支持两种重试策略，配合重试次数参数，应对不同故障场景。

#### 两种重试策略
- **current 策略（默认）**：调用失败后，继续重试同一台上游服务器
  - 适用场景：网络抖动导致的偶发失败，后端节点本身健康，重试同一节点即可恢复
  - 局限性：若节点本身故障，持续重试会加重故障，无法实现故障转移
- **failover 策略**：调用失败后，通过负载均衡算法选择其他健康节点重试
  - 适用场景：单节点硬件故障、进程挂掉等场景，自动切换到健康节点，提升服务可用性
  - 执行逻辑：后端有 3 个节点，第一次请求节点1失败 → 重试节点2 → 再失败重试节点3，直到无可用节点才返回错误

#### 关键注意事项
重试机制仅建议用于**幂等接口**（如查询类、只读类接口）；下单、支付等非幂等写操作接口开启重试，可能导致数据重复提交，需业务侧配合幂等设计。

## 三、原理层：底层执行流程与架构设计
### 1. 单次请求的插件执行全流程
Divide 插件继承自 `AbstractSoulPlugin`，一次 HTTP 请求的处理分为两个阶段：

#### 阶段一：前置匹配（父类统一逻辑）
1. 校验 Divide 插件是否启用，未启用则直接跳过
2. 遍历所有选择器，根据匹配条件筛选出命中的选择器
3. 在命中的选择器下，继续匹配对应的规则
4. 匹配成功后进入子类的 `doExecute` 核心逻辑

#### 阶段二：核心转发逻辑（DividePlugin 实现）
核心代码逻辑可拆解为 5 步：
1. **解析规则配置**：将规则中的 JSON 配置反序列化为 `DivideRuleHandle` 对象，获取负载均衡策略、超时时间、重试次数等参数
2. **获取可用节点**：从 `UpstreamCacheManager` 内存缓存中，读取当前选择器下所有健康的上游节点列表
3. **负载均衡选节点**：调用 `LoadBalanceUtils`，根据配置的负载均衡算法，从节点列表中选出一个目标上游节点
4. **组装转发参数**：拼接真实请求 URL，将目标 URL、超时时间、重试次数写入请求上下文（`ServerWebExchange`）
5. **继续插件链**：将请求传递给插件链下游，由底层 WebClient 组件完成真正的 HTTP 请求发送与响应接收

> 关键认知：**Divide 插件本身不发起 HTTP 请求**，只负责路由决策与参数组装，真正的响应式 HTTP 调用由下游专门的客户端插件完成，职责单一解耦。

### 2. 上游节点管理与健康检测机制
#### 双缓存节点管理
网关内存中维护两份节点映射：
- 全量节点缓存：存储选择器下所有注册的上游节点（包含离线节点）
- 可用节点缓存：仅存储健康检测通过的在线节点，Divide 插件负载均衡时只从该列表中选取

#### 健康检测机制
仅 HTTP 注册方式支持 upstream 健康检测，核心逻辑在 Admin 侧执行：
1. Admin 启动定时任务（默认 10 秒间隔），遍历所有 Divide 选择器的上游节点
2. 通过 HTTP 探活检测节点可用性，故障节点自动标记为离线
3. 节点状态变更后，通过数据同步通道（WebSocket/HTTP 长轮询）推送到所有网关节点，更新内存缓存
4. 长时间离线的「僵尸节点」会降低检测频率，减少性能损耗

### 3. 配置热更新原理
- 所有选择器、规则、上游节点配置均持久化在 Admin 侧的数据库中
- 配置变更后，Admin 实时通过数据同步通道将变更推送到网关的内存缓存
- Divide 插件每次请求均直接读取内存缓存中的配置，实现配置秒级生效，无需重启网关实例
- 服务上下线自动触发选择器节点列表更新，无需人工干预

## 四、实践层：生产环境落地与避坑指南
1. **重试策略选型**
   - 幂等查询类接口优先使用 failover 策略，提升可用性
   - 非幂等写接口建议关闭重试，或仅使用 current 策略应对偶发网络抖动
   - 重试次数建议不超过 2 次，避免故障时流量放大引发雪崩

2. **服务预热配置**
   - Java 业务服务建议将预热时间调大到 10~30 秒，适配 JVM JIT 编译、本地缓存加载过程
   - 新节点上线前可先预热，再逐步放开流量，降低上线风险

3. **一致性哈希边界**
   - 若网关前置有多层反向代理，`ip hash` 获取的可能是代理 IP，导致流量分布不均
   - 如需更精准的会话保持，可基于用户 ID 等业务字段自定义哈希键

4. **健康检测开启**
   - 生产环境务必开启 HTTP 注册方式的 upstream 健康检测，自动剔除故障节点
   - 可根据业务容忍度调整检测间隔、僵尸节点判定时间，平衡灵敏度与性能开销

5. **路径转发匹配**
   - 选择器与规则的路径匹配为完整路径匹配，需注意 `contextPath` 前缀的处理
   - 如需剥离前缀转发，需配合 ContextPath 重写插件使用，避免路径重复或缺失

### Docker 启动

```bash
cd /d/privategit/gitee/docker-compose/Windows/shenyu-2.6.1
docker compose -f docker-compose-ShenYu.yaml up -d
```

### Demo 多实例启动

```yaml
# application.yml (公共)
server.port: 8080
shenyu:
  client:
    http:
      props:
        contextPath: /http-demo11
        appName: http-demo11
        host: 10.19.236.150
        port: 8080
      serverLists: http://localhost:9096

---

# application-8179.yml
server.port: 8179
shenyu.client.http.props.port: 8179

---

# application-8189.yml
server.port: 8189
shenyu.client.http.props.port: 8189
```

IDEA Run Configuration: Spring Boot → Main class `ShenyuHttpDemoApplication` → Active profiles 填 `8179` 或 `8189`，两个 Config 均勾选 `Allow multiple instances`。

---

## 代码改造

### OrderController.java

```java
@RestController
@RequestMapping("/order")
public class OrderController {

    @Value("${server.port:8080}")
    private String serverPort;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    // 基础接口：返回实例标识
    @GetMapping("/findById")
    public Map<String, Object> findById(@RequestParam String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", "demo-order-" + id);
        result.put("serverPort", serverPort);
        result.put("instanceId", instanceId);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    // 超时模拟
    @GetMapping("/delay")
    public Map<String, Object> delay(@RequestParam(defaultValue = "100") long ms) {
        long start = System.currentTimeMillis();
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", instanceId);
        result.put("sleptMs", ms);
        result.put("actualMs", System.currentTimeMillis() - start);
        return result;
    }

    // 回显请求头 + 估算字节数（验证 headerMaxSize）
    @GetMapping("/echo-headers")
    public Map<String, Object> echoHeaders(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", instanceId);

        Map<String, String> headers = new HashMap<>();
        int totalBytes = 0;
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            headers.put(name, value);
            totalBytes += (value != null ? value.getBytes().length : 0);
        }
        result.put("receivedHeaders", headers);
        result.put("headerCount", headers.size());
        result.put("estimatedHeaderBytes", totalBytes);
        return result;
    }

    // 回显请求体字节数（验证 requestMaxSize）
    @PostMapping("/echo-body")
    public Map<String, Object> echoBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", instanceId);
        result.put("receivedBodyBytes", body.length);
        result.put("bodyPreview", new String(body, 0, Math.min(body.length, 200)));
        return result;
    }
}
```

> 注意：Spring Boot 2.7 使用 `javax.servlet.http.HttpServletRequest`，`readAllBytes()` 需 Java 9+。

---

## 规则配置入口

```
http://localhost:9096 → 插件列表 → Proxy → Divide → 选择器 /http-demo11
```

### MySQL 直改（当 admin UI 不生效时）

```sql
-- 查看规则
SELECT id, name, handle FROM shenyu_261.rule WHERE name = '/http-demo11/order/**';

-- 修改
UPDATE shenyu_261.rule SET handle = JSON_SET(handle,
  '$.headerMaxSize', 200,
  '$.requestMaxSize', 500,
  '$.timeout', 1000,
  '$.retry', 1
) WHERE name = '/http-demo11/order/**';

-- 改后重启 bootstrap 使生效
docker restart shenyu-bootstrap-261
```

> 从容器 jar 反编译确认：`headerMaxSize`/`requestMaxSize` 在值 `> 0` 时生效。`0` = 不限制。

---

## 验证用例与结果

### 1. 基础转发

**页面操作**：无需额外配置。demo 启动后自动注册选择器与规则，divide 插件开启即可。

```
登录 http://localhost:9096 (admin / 1qaz!QAZ)
→ 左侧菜单「基础配置」→「插件管理」→ 找到 divide → 确认状态为「开启」
→ 左侧菜单「插件列表」→ Proxy → Divide → 确认存在选择器 /http-demo11
```

```bash
curl -s "http://localhost:9196/http-demo11/order/findById?id=1"
# → {"serverPort":"8179","instanceId":"35d4f0c6",...}
```

| 判定 | 预期 | 实际 |
|------|------|------|
| HTTP 200 + `serverPort` 字段 | 200 | ✅ 200 |

---

### 2. Header 条件匹配（灰度路由）

**页面操作**：

```
① 登录 http://localhost:9096
② 左侧菜单「插件列表」→ Proxy → Divide → 选择器 /http-demo11
③ 右侧「添加规则」按钮
④ 填写：
   规则名称: /findById-gray
   匹配方式: AND
   条件: 类型=header, 参数名=X-Gray-Version, 运算符==, 参数值=v2
   处理: loadStrategy=roundRobin（保持默认即可）
   排序(sort): 1（比默认规则小，确保优先匹配）
⑤ 点「确认」保存
⑥ 等待 ~1s 让 WebSocket 同步到 bootstrap
```

```bash
# 带灰度 header → 命中灰度规则
curl -s -H "X-Gray-Version: v2" "http://localhost:9196/http-demo11/order/findById?id=1"
# → {"serverPort":"8189",...}

# 不带 header → 走默认规则
curl -s "http://localhost:9196/http-demo11/order/findById?id=1"
# → {"serverPort":"8179",...}
```

| 判定 | 预期 | 实际 |
|------|------|------|
| X-Gray-Version=v2 → 灰度规则 | 不同于默认 | ✅ 路由到 8189 |
| 无 header → 默认规则 | 默认路由 | ✅ 路由到 8179 |

---

### 3. 负载均衡

**页面操作**：

```
① 登录 → 插件列表 → Proxy → Divide → 选择器 /http-demo11
② 选择器编辑（确认两 upstream 均为开启状态）:
   10.19.236.150:8179  weight=50  status=开启
   10.19.236.150:8189  weight=50  status=开启
③ 点规则列表中的规则 → 编辑 → 处理配置 → 修改 loadStrategy → 点「确认」保存
④ 每次切换策略后等待 ~1s
```

#### roundRobin

```
loadStrategy = roundRobin
```

```bash
for i in $(seq 1 10); do
  curl -s "http://localhost:9196/http-demo11/order/findById?id=$i" | grep -o '"serverPort":"[0-9]*"'
done
# → 8179, 8189, 8179, 8189, ... (严格交替)
```

#### random

```
loadStrategy = random
```

```bash
for i in $(seq 1 20); do
  curl -s "http://localhost:9196/http-demo11/order/findById?id=$i" | grep -o '"serverPort":"[0-9]*"'
done | sort | uniq -c
# → 各 ~10 次，分布随机
```

#### hash (一致性哈希)

```
loadStrategy = hash
```

```bash
for i in $(seq 1 10); do
  curl -s "http://localhost:9196/http-demo11/order/findById?id=$i" | grep -o '"serverPort":"[0-9]*"'
done | sort | uniq -c
# → 全部落在同一端口（同 IP 粘性）
```

| 策略 | 预期 | 实际 |
|------|------|------|
| roundRobin | 严格交替 | ✅ 10 次 5:5 交替 |
| random | 两端口均有分布 | ✅ 接近均匀 |
| hash | 10 次全同一端口 | ✅ 全部 8189 |

---

### 4. 服务预热

**页面操作**：

```
① 登录 → 插件列表 → Proxy → Divide → 选择器 /http-demo11 → 编辑
② 在 upstream 列表找到 10.19.236.150:8189 → 修改:
   warmupTime: 60000  (毫秒，60秒)
   startupTime: <B 刚启动的时间戳(毫秒)>   ← 从 B 实例日志第一行获取，或用 date +%s%3N
   weight: 50  (不变)
③ 切换到规则列表 → 规则编辑 → loadStrategy 改回 roundRobin → 保存
④ 在 B 启动后 90s 内执行下方测试脚本
```

> 方式二 — 直接改 DB：重启 B 实例后立即记录时间戳，然后更新 MySQL：
> ```sql
> UPDATE shenyu_261.selector SET handle = JSON_SET(handle,
>   '$.upstreamHosts[1].warmupTime', 60000,
>   '$.upstreamHosts[1].startupTime', <时间戳>
> ) WHERE name = '/http-demo11';
> ```
> 同样需重启 bootstrap 或等 admin 同步。

```bash
for i in $(seq 1 30); do
  printf "%s  " "$(date +%H:%M:%S)"
  curl -s "http://localhost:9196/http-demo11/order/findById?id=$i" | grep -o '"serverPort":"[0-9]*"'
  sleep 3
done
```

公式：`实际权重 = 配置权重 × (运行时间 / warmupTime)`

| 时间段 | 预期 8189 占比 | 实际 |
|--------|---------------|------|
| 0~10s | ~10% | ✅ 低 |
| 30~60s | ~40% | ✅ 爬升 |
| 60s 后 | ~50% | ✅ 均衡 |

---

### 5. 超时重试

**页面操作**：

```
① 登录 → 插件列表 → Proxy → Divide → 选择器 /http-demo11
② 找到规则（若 /order/delay** 不存在，demo 启动时自动注册；否则手动添加）
③ 编辑规则 → 处理配置:
   timeout: 1000        (1秒超时)
   retryCount: 1        (重试1次，共2次尝试)
   loadStrategy: roundRobin  (配合 failover 测试观察实例切换)
④ 保存
```

#### current（同实例重试）

```
retryStrategy = current
```

```bash
curl -s "http://localhost:9196/http-demo11/order/delay?ms=3000"
# → 408 Request Timeout
# 观察两个 IDEA 控制台: 实例 A 日志出现 2 次 /order/delay，实例 B 日志 0 次
```

#### failover（切换实例重试）

```
retryStrategy = failover（页面修改后点确认保存）
```

```bash
curl -s "http://localhost:9196/http-demo11/order/delay?ms=3000"
# → 200 OK，{"serverPort":"8179"}
# A、B 各出现 1 次 /order/delay
```

| 策略 | 预期 | 实际 |
|------|------|------|
| current | A 日志 ×2，B ×0 | ✅ 408，A 重试 2 次 |
| failover | A 日志 ×1，B ×1 | ✅ 200，failover 到另一实例 |

---

### 6. headerMaxSize

**页面操作**：

```
① 登录 → 插件列表 → Proxy → Divide → 选择器 /http-demo11
② 找到规则 /order/echo-headers**（若不存在，demo 启动时自动注册）
③ 编辑规则 → 条件配置:
   URI 条件: 运算符=pathPattern, 参数值=/order/echo-headers
   （注意：不要写成 /http-demo11/order/echo-headers，需去掉 contextPath）
④ 处理配置 → headerMaxSize: 200
⑤ 点「确认」保存，等 ~1s
```

> **若 admin UI 修改后仍不生效**，直接改 MySQL + 重启 bootstrap：
> ```sql
> UPDATE shenyu_261.rule SET handle = JSON_SET(handle, '$.headerMaxSize', 200)
> WHERE name = '/http-demo11/order/**';
> ```
> ```bash
> docker restart shenyu-bootstrap-261
> ```

```bash
# 正常 — 通过
curl -s "http://localhost:9196/http-demo11/order/echo-headers"
# → {"estimatedHeaderBytes":102,...}

# 超限 — 拦截
LARGE=$(printf 'B%.0s' $(seq 1 200))
curl -s -H "X-Big: $LARGE" "http://localhost:9196/http-demo11/order/echo-headers"
# → {"code":431,"message":"Request Header Fields Too Large"}
```

| 测试 | 预期 | 实际 |
|------|------|------|
| 正常 header (~102B) | 200 | ✅ 200 |
| 超大 header (+200B) | 4xx | ✅ 431 |

> 实现：遍历所有 header values，sum 字节数，超 `headerMaxSize` 返回 431。单位：字节。`0` = 不限制。

---

### 7. requestMaxSize

**页面操作**：

```
① 登录 → 插件列表 → Proxy → Divide → 选择器 /http-demo11
② 找到规则 /order/echo-body**（demo 启动自动注册）
③ 编辑规则 → 条件配置:
   URI 条件: 运算符=pathPattern, 参数值=/order/echo-body
④ 处理配置 → requestMaxSize: 500
⑤ 点「确认」保存，等 ~1s
```

> 同 headerMaxSize，若 UI 不生效可改 MySQL：
> ```sql
> UPDATE shenyu_261.rule SET handle = JSON_SET(handle, '$.requestMaxSize', 500)
> WHERE name = '/http-demo11/order/**';
> ```

```bash
# 正常 — 通过
MED=$(printf 'C%.0s' $(seq 1 400))
curl -s -X POST -d "$MED" "http://localhost:9196/http-demo11/order/echo-body"
# → {"receivedBodyBytes":401,...}

# 超限 — 拦截
LARGE=$(printf 'D%.0s' $(seq 1 600))
curl -s -X POST -d "$LARGE" "http://localhost:9196/http-demo11/order/echo-body"
# → {"code":413,"message":"Request Entity Too Large"}
```

| 测试 | 预期 | 实际 |
|------|------|------|
| 正常 body (400B) | 200 | ✅ 200 |
| 超大 body (600B) | 4xx | ✅ 413 |

> 实现：读取 `Content-Length`，超 `requestMaxSize` 返回 413。单位：字节。`0` = 不限制。

---

## 字段速查

| 字段 | 位置 | 值 | 说明 |
|------|------|-----|------|
| `loadBalance` | 规则 | `roundRobin` / `random` / `hash` | 负载均衡策略 |
| `timeout` | 规则 | 毫秒 | 接口级超时 |
| `retry` | 规则 | 整数 | 失败后额外重试次数 |
| `retryStrategy` | 规则 | `current` / `failover` | 重试策略 |
| `headerMaxSize` | 规则 | 字节，`0`=不限 | 请求头总大小上限 |
| `requestMaxSize` | 规则 | 字节，`0`=不限 | 请求体大小上限 |
| `weight` | 选择器 upstream | 默认 50 | 负载权重 |
| `warmupTime` | 选择器 upstream | 毫秒 | 预热时长 |
| `startupTime` | 选择器 upstream | 时间戳(ms) | 预热基准时刻 |

## 注意

- 修改规则后 admin 通过 WebSocket 同步到 bootstrap，约 1s 延迟
- 规则条件 URI 匹配需去掉 contextPath（如 `/order/echo-headers` 而非 `/http-demo11/order/echo-headers`）
- delay 接口占 Tomcat 线程，仅用于重试验证
- `headerMaxSize`/`requestMaxSize` 修改后若 admin UI 不保存，直接改 MySQL + 重启 bootstrap
