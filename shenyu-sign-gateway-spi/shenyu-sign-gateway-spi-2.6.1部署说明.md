# shenyu-sign-gateway-spi 部署说明

> 解法A核心工程：网关侧 RSA 验签 SPI，验签上移到 SignPlugin。

## 打包

```bash
cd D:\privategit\github\shenyu-client-java\shenyu-sign-gateway-spi
mvn clean package -DskipTests
```

产物：`target/shenyu-sign-gateway-spi-2.6.1.jar`

## 部署到 Docker 网关

### 1. 拷贝 jar 到 bootstrap ext-lib

```bash
# 创建 ext-lib 目录（如果不存在）
mkdir -p D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-bootstrap\ext-lib

# 拷贝 jar
cp target/shenyu-sign-gateway-spi-2.6.1.jar \
   D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-bootstrap\ext-lib\
```

### 2. docker-compose 加 ext-lib 挂载

编辑 `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`，
在 `shenyu-bootstrap` 服务的 `volumes` 下加一行：

```yaml
  shenyu-bootstrap:
    # ... 现有配置
    volumes:
      - "./shenyu-bootstrap/conf:/opt/shenyu-bootstrap/conf"
      - "./shenyu-bootstrap/ext-lib:/opt/shenyu-bootstrap/ext-lib"   # 新增
      - "./shenyu-bootstrap/logs:/opt/shenyu-bootstrap/logs"
```

### 3. 重启 bootstrap

```bash
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1
docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 restart shenyu-bootstrap
```

### 4. 验证加载

```bash
# 看启动日志，应出现：
# [GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService
# [GW-Sign] 业务公钥已加载，将用于验签入站请求的 X-Pay-Sign
docker logs shenyu-bootstrap-261 2>&1 | grep "GW-Sign"
```

### 5. Admin 后台启用 sign 插件

1. 打开 http://localhost:9096，登录 admin / 1qaz!QAZ
2. 插件管理 -> 找到 `sign` 插件 -> 启用
3. 基础配置 -> 认证管理 -> 新增：
   - 应用名称：`sign-demo-pay`（与 PAY 注册的 appName 一致）
   - appKey：`biz-to-pay`（自定义）
   - appSecret：`placeholder`（RSA 方案不用对称密钥，填占位值）
4. sign 插件 Selector：匹配 `/pay-demo/v3/pay/**`，关联 appKey `biz-to-pay`
5. sign 插件 Rule：启用签名校验

## 解法A原理

```
插件执行顺序（切勿调乱）：
  SignPlugin(50) → RequestPlugin(100) → ContextPathPlugin(150) → DividePlugin(200)

SignPlugin 执行时：
  - 路径还是网关收到的原始路径：/pay-demo/v3/pay/transactions/jsapi（带 contextPath）
  - 与 BIZ 加签时用的路径完全一致
  → 验签天然匹配

ContextPathPlugin 执行时（SignPlugin 之后）：
  - 剥离 contextPath：/pay-demo/v3/pay/... → /v3/pay/...
  - PAY 收到的路径不带 contextPath
  - 但此时验签已在 SignPlugin 完成，PAY 不需要再验签
```



好的，现在我已经收集了足够的信息来构建一个深度技术分析。

挂载自定义 JAR 导致 Pod 漂移到其他 Node 的深层次原因分析

一、核心概念纠偏：Pod 为什么会“漂移”？

首先需要明确：Kubernetes 本身不会主动“移动”一个正在运行的 Pod。Pod 是 K8s 的最小调度单元，一旦绑定到某个 Node，其生命周期就固定在该 Node 上。

所谓的 “Pod 漂移”，本质上是一个 “旧 Pod 死亡/被驱逐 + 控制器重建新 Pod + 调度器将其分配到新 Node” 的三阶段过程。

[旧Pod异常] → (OOMKilled / Evicted / Preempted / 节点故障)
       ↓
[控制器重建] → Deployment/StatefulSet ReplicaSet 发现副本数不足，创建新 Pod
       ↓
[调度器决策] → Scheduler 根据资源、亲和性、污点等重新选择最优 Node (发生"漂移")

“挂载自定义 JAR”（如 Java Agent、热插拔插件、外挂依赖包）正是触发这个链条第一环的诱因。

二、深层次原因全链路剖析

原因链 1：JVM 内存模型与 Cgroup 限制的“认知错位”（最常见：OOMKilled）

这是生产环境中最典型、也最隐蔽的“漂移”原因。挂载自定义 JAR（尤其是 Java Agent / APM 探针 / 字节码增强工具）后，往往会引发非堆内存（Off-Heap）暴涨。

深层机制：
JVM 内存 ≠ 只有 Heap（堆）：
   Java 进程总内存 = Heap + Metaspace + Code Cache + Direct Memory + Thread Stacks + Native Memory (JNI/C++)。
   -Xmx 只能限制 Heap，无法限制其他区域。
自定义 JAR 的副作用：
   Metaspace 膨胀：如果自定义 JAR 使用了 CGLIB、ASM、ByteBuddy 等字节码动态生成技术（如自定义 Agent 拦截方法），会在运行时动态创建大量 Class，导致 Metaspace 无限膨胀。
   Direct Memory 泄漏：如果 JAR 中包含 Netty、NIO 相关的网络/存储组件，可能引发堆外内存泄漏。
   JNI 内存泄漏：如果 JAR 调用了底层 C/C++ 库（如加密、压缩算法），Native Memory 不受 JVM 管控。
Cgroup OOM 触发：
   当 Java进程总内存 > K8s Pod 的 resources.limits.memory 时，Linux 内核的 Cgroup OOM Killer 会直接 SIGKILL 杀掉 Java 进程。
   此时没有 JVM 的 OutOfMemoryError 日志，只会看到 Pod 状态变为 OOMKilled。

漂移路径：
Pod OOMKilled → CrashLoopBackOff → 如果触发了 Node 级别的 MemoryPressure，Kubelet 会直接 Evict（驱逐） 该 Pod → Deployment 重建 Pod → 调度器发现原 Node 内存紧张，将其调度到其他 Node → 表现为“漂移”。

原因链 2：存储卷挂载拓扑与 subPath 陷阱（ConfigMap/PVC 更新导致）

如果你的自定义 JAR 是通过 ConfigMap、Secret 或 PVC 挂载到容器中的，K8s 的存储机制会埋下隐患。

深层机制：
ConfigMap subPath 挂载陷阱：
   为了只挂载单个 JAR 文件而不覆盖目标目录下的其他文件，通常会使用 subPath：
          volumeMounts:
       name: custom-jar
         mountPath: /app/lib/my-plugin.jar
         subPath: my-plugin.jar
     
   致命缺陷：K8s 的机制是，当 ConfigMap 更新时，使用 subPath 挂载的容器无法自动感知文件更新。
   人为干预触发漂移：为了让新 JAR 生效，运维人员通常会执行 kubectl delete pod 或触发 Rolling Update。此时新 Pod 重建，调度器可能根据当前的集群资源水位，将其分配到其他 Node。
PVC 的 RWO（ReadWriteOnce）拓扑限制：
   如果自定义 JAR 放在 PVC 中（如云盘），且访问模式为 ReadWriteOnce。当原 Node 发生网络抖动或 Kubelet 假死时，旧 Pod 卡在 Terminating 状态，Volume 无法卸载。
   控制器创建的新 Pod 无法挂载到原 Node（或一直 Pending），某些存储插件（CSI）在超时后可能会强制 Detach 并允许挂载到其他 Node，从而导致 “被动漂移”。

原因链 3：Init Container 资源争抢与探针超时（启动阶段崩溃）

很多团队使用 Init Container + EmptyDir 的模式来下载或拷贝自定义 JAR（例如从 OSS/Nacos 拉取最新插件）。

深层机制：
Init Container 拖慢启动：下载大体积 JAR 包或进行解压、校验，耗时过长。
主容器启动探针（Startup Probe）超时：
   挂载自定义 JAR 后，JVM 启动时需要加载额外的类、执行 Agent 的 premain 方法，导致启动时间翻倍。
   如果 K8s 的 startupProbe 或 livenessProbe 配置的时间不够，Kubelet 会认为容器卡死，不断重启容器。
节点资源耗尽引发驱逐：
   频繁的探针失败和重启会导致该 Node 上的 CPU/IO 飙升。如果该 Node 本身资源紧张，Kubelet 会根据 QoS 等级（Burstable/BestEffort） 驱逐该 Pod。
   重建后的 Pod 被调度到资源更健康的 Node。

原因链 4：安全沙箱与 Seccomp/AppArmor 拦截

自定义 JAR 如果包含不安全的系统调用（如 JNI 调用底层驱动、动态修改内核参数、不规范的 /tmp 读写），可能会触发 K8s 节点的安全策略。

深层机制：
某些 Node 配置了 Seccomp Profile 或 AppArmor/SELinux。自定义 JAR 的某些 Native 操作被拦截，导致 JVM 直接 Segmentation Fault（段错误）崩溃。
这种崩溃表现为 Exit Code 139 或 134。
如果 DaemonSet 或控制器检测到该 Node 环境不兼容（通过自定义的准入控制器或健康检查），可能会通过 Taint/Toleration（污点/容忍） 机制，将该 Pod 驱逐并调度到没有该安全限制的其他 Node。

三、如何排查与定位？

如果你正在经历这个问题，请按以下“四步法”进行排查：
步骤   排查命令 / 动作   关注点
1. 查死因   kubectl get pod <old-pod-name> -o yaml (或查看历史事件)   看 Last State 中的 Reason 是 OOMKilled、Evicted 还是 Error。

2. 查驱逐   kubectl get events --field-selector involvedObject.name=<pod-name>   寻找 The node was low on resource: [memory] 或 DiskPressure 等 Kubelet 驱逐事件。

3. 查内存   进入容器执行 jcmd <pid> VM.native_memory summary   开启 NMT (Native Memory Tracking)，对比挂载自定义 JAR 前后的堆外内存（Metaspace/Internal/Symbol）变化。

4. 查挂载   kubectl describe pod <new-pod-name>   检查 Volume 挂载方式，确认是否使用了 subPath，以及 ConfigMap/PVC 的更新策略。

四、终极防御方案（Best Practices）

内存隔离与限制（针对 OOM 漂移）：
   必须开启 NMT：在 JAVA_OPTS 中加入 -XX:NativeMemoryTracking=detail，方便排查堆外泄漏。
   限制 Metaspace：明确设置 -XX:MaxMetaspaceSize=256m（根据实际评估），防止字节码增强工具吃光内存。
   合理设置 Limit：Pod 的 limits.memory 至少应为 JVM -Xmx 的 1.5 倍 到 2 倍，为堆外内存和 OS Page Cache 留出余量。
   开启容器感知：确保使用 JDK 8u191+ 或 JDK 11+，并开启 -XX:+UseContainerSupport，让 JVM 正确识别 Cgroup 限制。

优雅挂载策略（针对存储漂移）：
   放弃 subPath：尽量将自定义 JAR 打包进基础镜像，或者使用 Init Container 将 JAR 拷贝到 EmptyDir 中，主容器挂载整个 EmptyDir 目录。这样既避免了 subPath 不更新的坑，又解耦了存储拓扑。
   使用 OCI Image Volume (K8s 1.31+)：如果是较新的 K8s 集群，可以将 JAR 打包成 OCI 镜像，使用 image 类型的 Volume 直接挂载，这是目前最优雅的外挂依赖方案。

探针与启动优化（针对启动崩溃）：
   增加 startupProbe 的 failureThreshold * periodSeconds，给加载了自定义 JAR 的 JVM 足够的预热时间（如 3-5 分钟）。

总结
“挂载自定义 JAR 导致 Pod 漂移”的表象下，90% 的情况是自定义 JAR 引入了堆外内存泄漏或 Metaspace 膨胀，导致触碰了 K8s 的 Cgroup 内存天花板，触发了 OOMKilled 或 Kubelet 驱逐。剩下的 10% 则与 K8s 的 subPath 挂载缺陷或 PVC 拓扑强绑定有关。解决的核心在于打通 JVM 内存模型与 K8s Cgroup 资源边界的认知壁垒。