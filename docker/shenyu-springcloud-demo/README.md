# shenyu-springcloud-demo（Docker 编排资源）

本目录存放「Spring Cloud + ShenYu 2.6.1 springCloud 插件 + Nacos 2.5.3」demo 所需的 Docker 编排资源。
**不直接修改** gitee/docker-compose 仓库（外部），所有对既有 compose 的改动以 patch 文档形式提供。

> 业务侧代码在仓库根的 [`shenyu-springcloud-demo/`](../../shenyu-springcloud-demo) 子模块。
> 完整的启动顺序 / admin 控制台配置 / curl 验证见
> [`docs/springcloud-plugin-验证手册.md`](../../docs/springcloud-plugin-验证手册.md)。

## 目录结构

```
docker/shenyu-springcloud-demo/
├── README.md                                  本文件
├── nacos-2.5.3/                               Nacos 2.5.3 容器（2.5.x 末版，2026-07-14 发布）
│   └── docker-compose-nacos-2.5.3.yml
├── bootstrap-overlay/
│   └── application-overlay.yml                shenyu-bootstrap 开启 Nacos discovery 的目标终态配置（参考用）
└── compose-patches/
    ├── shenyu-261-bootstrap-attach-nacos-net.patch.yaml   对 gitee 仓库 docker-compose-ShenYu.yaml 的补丁
    └── README.md                              补丁的应用步骤
```

## 设计原则

1. **不污染外部仓库**：gitee/docker-compose 是共享仓库，所有改动以 patch 文档形式留本仓库。
2. **不重编 ShenYu 镜像**：stock `apache/shenyu-bootstrap:2.6.1` 已内置 springCloud 插件 +
   spring-cloud-starter-alibaba-nacos-discovery（v2.6.1 tag 的 `shenyu-bootstrap/pom.xml` 里两者都是默认
   `<dependencies>`），仅被 conf/application.yml 关闭；通过 compose env 开启即可。
3. **不写业务 SQL**：仅初始化 nacos_config_253 建表脚本 + 查询 ShenYu admin 表的 SELECT 语句。
4. **每大版本（major.minor）仅保留最高 patch**：本目录仅保留 `nacos-2.5.3/`（2.5.x 末版），
   不再保留 2.5.1 等历史 patch。如需 2.5.x 其他版本，自行修改 compose 的 image tag 即可。

## 关键事实速查

| 项 | 值 |
|---|---|
| Nacos server | `2.5.3`（容器 `nacosserver253`；2.5.x 末版，2026-07-14 发布） |
| Nacos 库 | `nacos_config_253`（在 mysql57 / 3306） |
| Nacos 端口 | `8848`（HTTP）/ `9848`（gRPC sdk）/ `9849`（gRPC cluster） |
| Nacos 网络 | `nacos_net_232`（复用历史网络名） + `mysql_default`（external） |
| ShenYu admin | 2.6.1 stock，宿主端口 `9096`（容器 9095） |
| ShenYu bootstrap | 2.6.1 stock，宿主端口 `9196`（容器 9195）；patch 后接入 `nacos_net_232` |
| demo 微服务 | `shenyu-springcloud-demo`，宿主端口 `8470`，Nacos serviceId = `shenyu-springcloud-demo` |
| 网关路径前缀 | `/springcloud-demo`（`shenyu.client.springCloud.props.contextPath`） |
| nacos-client 版本 | `2.0.4`（与镜像自带一致；官方规则兼容 server 2.5.3） |
| Spring Cloud Alibaba | `2021.0.1.0`（与 ShenYu 2.6.1 内置一致） |

## Nacos 2.5.x 版本线（官方 release-history）

| 版本 | 发布日期 |
|---|---|
| 2.5.0 | 2025-01-21 |
| 2.5.1 | 2025-03-11 |
| 2.5.2 | 2025-11-17 |
| **2.5.3** ← 本方案 | **2026-07-14** |

> 公共 docker mirror 都没同步 2.5.x 新版（包括 2.5.1/2.5.2/2.5.3），需用华为云 ddn-k8s 拉取：
> ```shell
> docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3
> docker tag swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/nacos/nacos-server:v2.5.3 nacos/nacos-server:2.5.3
> ```

## 通道划分（避免混淆）

同一个 Nacos 地址在 ShenYu 体系里承担三个相互独立的平面，本 demo 用的：

| 通道 | 配置位置 | 协议 | 用途 |
|---|---|---|---|
| ① 元数据注册 | `shenyu.register.registerType: http`（demo 侧 application.yml） | HTTP → shenyu-admin | 把 `@ShenyuRequestMapping` 的 path 推给 admin |
| ③ 服务实例发现 | `spring.cloud.nacos.discovery`（demo 注册、bootstrap 拉取） | gRPC | springCloud 插件据此路由转发 |

通道②（`shenyu.sync.nacos` 配置同步）本 demo 未启用，bootstrap↔admin 走 websocket 同步。

## 一键启动顺序（详见验证手册）

```shell
# 1) 停掉当前运行的 nacos（8848 端口互斥）
docker stop nacosserver251 nacosserver253 nacosserver223 nacosserver231 nacosserver232 nacosserver204 2>/dev/null

# 2) 启动 nacos 2.5.3
cd D:/privategit/github/shenyu-client-java/docker/shenyu-springcloud-demo/nacos-2.5.3
docker-compose -f docker-compose-nacos-2.5.3.yml -p nacos253 up -d

# 3) 初始化 nacos_config_253 库（首次）—— 见验证手册 b 节
docker exec mysql57 mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS nacos_config_253 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint cat nacos/nacos-server:2.5.3 /home/nacos/conf/mysql-schema.sql | docker exec -i mysql57 mysql -uroot -proot nacos_config_253

# 4) 应用 compose patch 重启 bootstrap（见 compose-patches/README.md）

# 5) admin 控制台开启 springCloud 插件 + 配 selector/rule（见验证手册 c 节）

# 6) 启动 shenyu-springcloud-demo
cd D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo
mvn spring-boot:run
```

## 验证

```shell
# demo 直接访问（不经网关）
curl http://127.0.0.1:8470/order/findById?id=42

# 经网关访问（springCloud 插件 + Nacos discovery）
curl http://127.0.0.1:9196/springcloud-demo/order/findById?id=42
```
