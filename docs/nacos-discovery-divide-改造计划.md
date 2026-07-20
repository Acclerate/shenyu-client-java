# ShenYu Divide 插件对接 Nacos 服务发现 — 可行性分析与改造计划

> 目标：让已注册到 Nacos 的 `shenyu-http-demo`（8179 / 8189 两实例）以 **Nacos 服务发现** 的方式
> 成为 divide 插件 `/http-demo11` 选择器的上游（upstream），实现上游列表随 Nacos 实例上下线自动增删。

---

## 0. 结论速览（可行性）

**可行。** 所需的服务发现监听组件在当前环境中已经具备，无需重构代码，主要是 **配置 + 数据层改造**。但有 3 个必须先处理的关键障碍。

| 维度 | 结论 |
|------|------|
| 客户端实例注册到 Nacos | ✅ 已完成且在线：`shenyu-http-demo` / DEFAULT_GROUP / public，2 个健康实例（8179、8189） |
| 网关是否需要加 `shenyu-discovery-nacos` | ❌ **不需要**。2.6.1 的 Nacos 监听器运行在 **admin**，不在网关 |
| admin 是否已具备 Nacos 发现能力 | ✅ 已内置 `shenyu-discovery-nacos-2.6.1.jar` + `shenyu-discovery-api` |
| 当前 divide 选择器的发现类型 | ⚠️ 全部为 `local`，且 `/http-demo11` 选择器**未绑定任何发现对象**、无上游 |
| 主要风险 | ①admin 的 `nacos-client` 版本——**已不构成阻塞**（Nacos 鉴权已关，gRPC 握手失败风险解除，仅留作验证）②2.6.1 UI 发现配置"创建后锁定" ③重复脏选择器 ④serverList 网络地址 |

---

## 1. 现状核查结果（实测证据）

运行环境（Docker，项目名 `shenyu261`）：

| 组件 | 容器名 | 地址 | 版本 |
|------|--------|------|------|
| Nacos Server | `nacos_server_232` | 宿主 8848 / 容器网络 `nacos_server_232:8848` | v2.3.2（`NACOS_AUTH_ENABLE=false`，**鉴权已关闭**）|
| shenyu-admin | `shenyu-admin-261` | `localhost:9096` | 2.6.1 |
| shenyu-bootstrap（网关）| `shenyu-bootstrap-261` | `localhost:9196` | 2.6.1 |
| demo（IDEA 多实例）| — | `10.19.236.150:8179` / `:8189` | Spring Boot 2.7.18 |
| MySQL | `mysql57` | 库 `shenyu_261` | 5.7 |

### 1.1 Nacos 侧（客户端注册已生效）

```
service: DEFAULT_GROUP@@shenyu-http-demo  →  healthyInstanceCount = 2
  - 10.19.236.150:8179  healthy=true
  - 10.19.236.150:8189  healthy=true
```

### 1.2 网关（bootstrap）依赖：**无任何 shenyu-discovery-\* 模块**

`/opt/shenyu-bootstrap/lib/` 内只有 `shenyu-registry-*`（注册中心）与 `shenyu-sync-data-*`（配置同步），
**没有 `shenyu-discovery-nacos`**。这印证了：网关不做 Nacos 监听，只被动接收 admin 同步来的上游数据。

### 1.3 admin 依赖：**已内置发现监听器**

```
shenyu-discovery-api-2.6.1.jar
shenyu-discovery-nacos-2.6.1.jar   ← 关键：Nacos 实例监听在这里
shenyu-discovery-zookeeper/etcd/eureka-2.6.1.jar
nacos-client-2.0.4.jar             ← 风险点，见 §3.1
```

### 1.4 数据库当前状态（`shenyu_261`）

- `discovery` 表：2 行，均为 `type=local`，`server_list=NULL`、`props=NULL`（名为 `divide_default_discovery`）。
- `discovery_handler`：2 行，`handler={}`、`listener_node=NULL`、`props={}`（空壳）。
- `discovery_rel`：把上述发现对象绑定到 **`/pay-center`、`/pay-demo`**（历史 sign/pay 遗留），**与 `/http-demo11` 无关**。
- `discovery_upstream`：仅 1 行陈旧数据 `10.19.236.150:8092`。
- **`/http-demo11` 选择器存在 3 条重复记录**（id 结尾 …910784 / …628736 / …315840），`handle` 均为 NULL，
  **没有任何 discovery_rel 绑定、没有上游**。
- 仓库根目录的 `fix_discovery.sql` 里的 id（`fbebc6a5…` / `49f60274…`）与当前库中雪花 id 不匹配，**已失效，勿直接执行**。

> 核心差距一句话：**`/http-demo11` 选择器当前没有绑定 Nacos 发现对象，上游为空。改造就是给它建立一个 `type=nacos` 的发现绑定，让 admin 订阅 `shenyu-http-demo` 服务并把 2 个实例喂成上游。**

---

## 2. 架构与原理（为什么这样改）

ShenYu 有两条独立链路，务必区分：

1. **注册中心（register）**：客户端把 *元数据 / 选择器 / 规则 / URI* 推给 admin。
   demo 当前 `registerType: http`，`/http-demo11` 选择器就是这么建出来的。
2. **服务发现（discovery）**：让网关的上游列表来自注册中心的实时实例，而非 HTTP 静态推送。
   - `shenyu.discovery.register=true` → 客户端把自身实例写进 Nacos（**已生效**）。
   - admin 侧的 `NacosDiscoveryService`（在 `shenyu-discovery-nacos`）**订阅** Nacos 指定服务，
     实例变化 → 写 `discovery_upstream` → 通过数据同步通道（WebSocket）下发给网关 → 网关更新 divide 上游缓存。

```
demo(8179/8189) ──注册实例──▶ Nacos(shenyu-http-demo)
                                   │ 监听/订阅
                                   ▼
        shenyu-admin (NacosDiscoveryService) ──写 discovery_upstream──▶ MySQL
                                   │ WebSocket 同步
                                   ▼
        shenyu-bootstrap(divide 上游缓存) ──负载均衡转发──▶ 8179/8189
```

**关键推论**：监听方是 admin。因此 admin 的 `nacos-client` 必须能与 Nacos 2.3.2 正常 gRPC 订阅
（~鉴权已关闭，`NACOS_AUTH_ENABLE=false`，原 gRPC 鉴权握手失败的隐患已消除~）；
网关无需任何改动。

---

## 3. 关键风险与对策（改造前必须评估）

### 3.1 【低·已解除】admin 的 nacos-client 2.0.4 与 Nacos 2.3.2 gRPC 订阅
demo 的 pom 注释曾记录：nacos-client 2.0.4 对 Nacos 2.3.2 **开鉴权**时，gRPC `InstanceRequest`
被拒（"Connection is unregistered"），HTTP OpenAPI 正常但 gRPC 订阅/注册失败，客户端侧因此升到 2.2.4。
但本环境 Nacos `NACOS_AUTH_ENABLE=false`（鉴权关闭），**该问题已不成立**：
- 已实测验证：不带账号直接访问 `/nacos/v1/ns/instance/list` 返回 `HTTP 200`，说明无鉴权握手负担；
- admin 的 `nacos-client 2.0.4` 走 gRPC 订阅应可正常工作（同版本客户端此前在"无鉴权"场景下注册成功）。
- **保留为验证项而非阻塞项**：阶段 C 后看 admin 日志能否订阅到实例即可；万一仍失败（与鉴权无关的其他兼容问题），
  再把 admin 的 `nacos-client-2.0.4.jar` 升到 `2.2.4`（放 `shenyu-admin/ext-lib` 或替换 `lib/`，重启 admin）。

### 3.2 【中】2.6.1 UI「服务发现配置创建后不可编辑」
2.6.1 中 divide 选择器的 Discovery Config 仅在 **新建选择器时** 可填，编辑时锁定（官方有意设计）。
现有 `/http-demo11` 选择器是 HTTP 注册自动建出来的（local），**无法通过"编辑"改成 nacos**。
- **对策**：删除旧选择器后用"新建选择器 + 服务发现标签页"重建；或走 §4.4 的 DB 直改 + 重启 admin。

### 3.3 【中】serverList 必须用容器网络地址，不能用 localhost
客户端在宿主机，`serverList: localhost:8848` 正确；但 **admin 在容器内**，其发现对象的 serverList
必须是 `nacos_server_232:8848`（见仓库 `discovery-config.yml`）。若误存成 `localhost:8848`，
admin 在容器内订阅会失败。

### 3.4 【低】重复脏选择器 / 遗留发现对象
`/http-demo11` 有 3 条重复选择器；`discovery`/`discovery_rel` 有 pay 相关遗留。改造前应清理，避免绑定错对象。

### 3.5 【低】namespace / props 键名匹配
客户端用 `nacosNameSpace:""`（public）。admin 发现对象 props 的键名需与 2.6.1 `NacosDiscoveryService`
期望一致（在 admin →基础配置→字典管理，字典名 `nacos` 可查默认键）。namespace 为 public 时通常留空。

---

## 4. 改造步骤（分阶段）

> 约定：所有 SQL 均针对库 `shenyu_261`；改动后按提示重启对应容器。**先备份数据库**。

### 阶段 A — 前置验证（只读，不改动）
1. 确认 Nacos 实例在线：`shenyu-http-demo` 有 2 个 healthy 实例（§1.1 已确认 ✅）。
2. 确认 admin 有 `shenyu-discovery-nacos`（§1.3 已确认 ✅）。
3. 记录 admin 当前 `nacos-client` 版本（2.0.4，待观察 §3.1）。
4. 备份：`docker exec mysql57 mysqldump -uroot -proot shenyu_261 > backup_before_nacos_discovery.sql`

### 阶段 B — 清理脏数据
1. 保留 1 条 `/http-demo11` 选择器，删除多余 2 条重复选择器及其规则（记录保留的 selector_id）。
2. 清理与 `/http-demo11` 无关且不再需要的 pay 遗留发现对象（可选，谨慎）。
3. 删除 `discovery_upstream` 中的陈旧 `10.19.236.150:8092`。

### 阶段 C — 建立 Nacos 发现绑定（三选一，推荐 C1）

**C1｜Admin UI 重建选择器（推荐，最贴合官方流程）**
1. Admin（`localhost:9096`，admin / `1qaz!QAZ`）→ 插件列表 → Proxy → Divide。
2. 删除旧 `/http-demo11` 选择器 → 点「添加选择器」。
3. 切到「服务发现」标签页：
   - 类型（type）：`nacos`
   - 监听节点（listenerNode）：`shenyu-http-demo`（= Nacos 中的服务名）
   - 服务器URL（serverList）：`nacos_server_232:8848`
   - 注册中心参数（props）：`groupName=DEFAULT_GROUP`、`namespace=`（空=public）。**鉴权已关闭（NACOS_AUTH_ENABLE=false），`username`/`password` 可不填**，留空即可（填了也无碍但无必要）。
   - 如有「导入后台服务发现配置」，可点它复用客户端已推送的配置，再仅填 listenerNode。
4. 选择器条件：URI `pathPattern` = `/http-demo11/**`（或与原一致）；保存。
5. 补建/确认规则（`/http-demo11/**`，loadStrategy=roundRobin）。

**C2｜客户端驱动（沿用 shenyu.discovery 推送）**
- 校正 demo `application.yml` 中 discovery 配置，使 **admin 侧看到的 serverList=容器地址**、
  `registerPath`（即被监听服务名）与 Nacos 实际服务名 `shenyu-http-demo` 一致，重启 demo 触发推送。
- ⚠️ 实测本环境该路径未能为 `/http-demo11` 生成 nacos 发现对象（当前仍是 local），可靠性低，仅作备选。

**C3｜数据库直改 + 重启 admin（UI 不生效时兜底）**
按当前真实雪花 id 生成新 SQL（不要用失效的 `fix_discovery.sql`）：
```sql
-- 伪代码，<SEL_ID> 为保留的 /http-demo11 selector_id，<DISC_ID>/<HANDLER_ID> 用新雪花或唯一串
UPDATE discovery SET type='nacos', server_list='nacos_server_232:8848',
  props='{"nacosNameSpace":"","groupName":"DEFAULT_GROUP"}'
  WHERE id='<DISC_ID>';
UPDATE discovery_handler SET listener_node='shenyu-http-demo',
  props='{"groupName":"DEFAULT_GROUP"}' WHERE id='<HANDLER_ID>';
INSERT INTO discovery_rel(id,plugin_name,discovery_handler_id,selector_id)
  VALUES('<REL_ID>','divide','<HANDLER_ID>','<SEL_ID>');
```
```bash
docker restart shenyu-admin-261   # 让 DiscoveryProcessor 重新初始化并订阅 Nacos
```

### 阶段 D — 处理 nacos-client 版本风险（若阶段 C 后订阅失败）
1. 看 admin 日志：`docker logs shenyu-admin-261 | grep -iE "nacos|discovery|subscribe|unregister"`。
2. 若出现 gRPC 握手/未注册错误：把 `nacos-client` 升到 2.2.4
   （放 `shenyu-admin/ext-lib/nacos-client-2.2.4.jar` 或替换镜像内 `lib/`），重启 admin 复测。

---

## 5. 验证清单

| # | 验证点 | 方法 | 预期 |
|---|--------|------|------|
| 1 | admin 成功订阅 Nacos | `docker logs shenyu-admin-261` | 有订阅 `shenyu-http-demo` 日志，无 gRPC 报错 |
| 2 | 上游写入库 | `SELECT url,status FROM discovery_upstream` | 出现 `10.19.236.150:8179`、`:8189`，status=0 |
| 3 | 选择器上游列表 | Admin UI → divide → `/http-demo11` 上游列表 | 显示 2 个实例，URL 不可手工改（非 local） |
| 4 | 网关转发+负载均衡 | `for i in {1..10}; do curl -s localhost:9196/http-demo11/order/findById?id=$i; done` | 8179/8189 交替返回 |
| 5 | 动态上下线 | 停掉 8189 实例 | 上游自动剔除 8189，请求全落 8179；重启后自动恢复 |

---

## 6. 回滚方案
1. 恢复库：`docker exec -i mysql57 mysql -uroot -proot shenyu_261 < backup_before_nacos_discovery.sql`。
2. 把发现对象改回 `type=local` 或删除 discovery_rel 绑定，重启 admin。
3. 如替换过 nacos-client，移除 ext-lib 新 jar / 还原镜像，恢复 2.0.4。
4. demo 侧无需回滚（实例注册到 Nacos 不影响原 HTTP 转发）。

---

## 7. 一句话总结
监听组件（admin 的 `shenyu-discovery-nacos`）与数据源（Nacos 的 2 个健康实例）都已就位，
**改造 = 给 `/http-demo11` 选择器建立一个 `type=nacos` 的发现绑定（指向服务名 `shenyu-http-demo`、
serverList 用容器地址）**。原担心的 nacos-client 2.0.4 鉴权 gRPC 坑已因 Nacos 鉴权关闭而解除，
整体属配置/数据层改造，风险可控、可回滚。

---

## 8. 执行结果（2026-07-16 已成功打通端到端）✅

实际落地用 **C3（DB 直改 + 重启 gateway）**，过程中发现并修复了计划外的两个坑：

### 8.1 实操中暴露的两个新坑（计划未预见）
1. **serverList 下划线 gRPC 坑**：原计划写 `nacos_server_232:8848`，但容器名含下划线 `_`，admin 的 nacos-client 走 gRPC(Netty) 报 `Illegal character in hostname //nacos_server_232:9848`，握手失败 200+ 次，保存回滚导致 UI 上 type 始终为空。
   - **解法**：用 nacos 容器在 `mysql_default` 网络的无下划线别名 `nacos:8848`（admin 同网可达，`nacos:9848` nc 实测通）。
2. **divide 选择器消失**：demo 通过 HTTP 注册建的 divide `/http-demo11` 选择器在 admin 重启后被清/替换，`selectorMapper.selectByDiscoveryId` 查不到 → `fetchAll`/`watch` 不触发 → 上游为空。
   - **解法**：手动新建一条稳定的 divide 选择器（不动 admin、不依赖 demo 重注册）。

### 8.2 最终落库数据（库 `shenyu_261`）
| 表 | id | 关键字段 |
|----|-----|---------|
| discovery | `2079990010000000001` | type=nacos, server_list=`nacos:8848`, props=`{"nacosNameSpace":"","groupName":"DEFAULT_GROUP"}`, level=0, plugin_name=divide |
| discovery_handler | `2079990010000000002` | discovery_id=上, listener_node=`shenyu-http-demo`, handler=`{}` |
| discovery_rel | `2079990010000000003` | plugin_name=divide, discovery_handler_id=上, selector_id=`2079990020000000001` |
| selector | `2079990020000000001` | plugin_id=5(divide), name=/http-demo11, handle=NULL(discovery模式) |
| selector_condition | `2079990020000000002` | uri startsWith `/` `/http-demo11/` |
| rule | `2079990020000000003` | name=/http-demo11/**, handle=`{"loadBalance":"roundRobin",...}` |
| rule_condition | `2079990020000000004` | uri pathPattern `/` `/http-demo11/**` |

### 8.3 触发与验证
- `docker restart shenyu-bootstrap-261`（**只重启 gateway，不重启 admin**，避免 demo 重注册清选择器）→ admin 收到 websocket syncAll → `DiscoveryServiceImpl.syncData` → `Subscribed to Nacos updates for key: shenyu-http-demo`。
- `discovery_upstream` 落入：`10.19.236.150:8179`、`:8189`，status=0(healthy)，protocol=`http://`（自动，无需补）。
- `curl localhost:9196/http-demo11/order/findById?id=N`：10 次请求 `8189/8179/8189/8179...` 完美 roundRobin 轮询，HTTP 200。
- gateway 日志：`divide selector success match, selector name:/http-demo11` + `divide rule success match, rule name:/http-demo11/**`。

### 8.4 可复用配方（踩坑总结）
1. admin 连 nacos 的 serverList **必须用无下划线别名 `nacos:8848`**，禁用 `nacos_server_232:8848`。
2. discovery.props **必须显式 `groupName=DEFAULT_GROUP`**（默认 SHENYU_GROUP 会订阅不到 demo 实例）。
3. handler.listener_node **必须 = Nacos 实际服务名**（=客户端 props.name = `shenyu-http-demo`）。
4. 需有 divide 选择器（`uri startsWith /contextPath/`）+ 规则（`uri pathPattern /contextPath/**`，`loadBalance=roundRobin`）；discovery 模式下 selector.handle 可为 NULL。
5. **用重启 gateway 触发 syncData**，不要重启 admin（admin 重启会触发 demo 重注册、清掉选择器）。
6. discovery 的 type 可选 selector 级（level=0），无需插件级。

> 备份：`backup_before_c3.sql`（mysqldump shenyu_261）。回滚：`docker exec -i mysql57 mysql -uroot -proot shenyu_261 < backup_before_c3.sql` 后重启 admin。
