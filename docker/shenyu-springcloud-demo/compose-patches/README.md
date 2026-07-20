# compose-patches/

存放对 **gitee/docker-compose** 仓库（外部）现有 compose 文件的补丁说明。本仓库不直接修改 gitee 仓库文件，
所有改动以 patch 文档形式记录，用户手动应用到目标 compose。

## 文件清单

### `shenyu-261-bootstrap-attach-nacos-net.patch.yaml`

**目标文件**：`D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`

**改动摘要**（共 2 处 + 1 个顶层 networks 项）：

| # | 位置 | 改动 | 原因 |
|---|---|---|---|
| 1 | `services.shenyu-bootstrap.networks` | 新增 `- nacos_net`（引用 external `nacos_net_232`） | stock 配置 bootstrap 不在 nacos 网络上，无法解析 `nacosserver253` |
| 2 | `services.shenyu-bootstrap.environment` | 注入 `SPRING_CLOUD_*` / `SHENYU_SPRINGCLOUDCACHE_*` 等环境变量 | 覆盖 stock conf/application.yml 里关闭的 Nacos discovery |
| 3 | 顶层 `networks.nacos_net` | 新增 external 声明（name: `nacos_net_232`） | 引用已存在网络，避免 compose 自建 |

## 应用步骤

1. **先把 nacos 2.5.3 启起来**（见 `../nacos-2.5.3/docker-compose-nacos-2.5.3.yml`），确保 `docker network ls` 能看到 `nacos_net_232`。
2. **手动编辑** `D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\docker-compose-ShenYu.yaml`：
   - 按 patch 文件里的「改动 1 / 2 / 3」逐项添加/修改。
   - 注意保留原有 `shenyu.sync.websocket.urls` 等环境变量，不要删。
3. **验证 compose 解析**（dry-run）：
   ```shell
   cd D:/privategit/gitee/docker-compose/Windows/shenyu-2.6.1
   docker-compose -f docker-compose-ShenYu.yaml config | grep -E "nacosserver253|nacos_net_232|SPRING_CLOUD_NACOS"
   ```
   期望输出包含 `nacosserver253:8848`、`nacos_net_232`、`SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=true` 等。
4. **重启 bootstrap**（不需要动 admin）：
   ```shell
   docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d shenyu-bootstrap
   ```
5. **连通性验证**：
   ```shell
   docker exec shenyu-bootstrap-261 sh -c "wget -q -O - http://nacosserver253:8848/nacos/v1/ns/operator/metrics"
   ```
   期望返回 JSON（含 `status":"UP"`），证明 bootstrap 已能按容器名解析并访问 nacos。

## 回滚

如需关闭 springCloud 插件路径，恢复到 divide + @ShenyuSpringMvcClient 模式：

1. 编辑 `docker-compose-ShenYu.yaml`，删除 patch 引入的 `networks.nacos_net`、`environment` 里的 `SPRING_CLOUD_*` / `SHENYU_SPRINGCLOUDCACHE_*` 项。
2. 重启 bootstrap：`docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d shenyu-bootstrap`
3. admin 控制台「插件管理 → springCloud」设为关闭。

> 注意：本 patch 与 `shenyu-sign-gateway-spi` 的 patch（`D:\privategit\github\shenyu-client-java\shenyu-sign-gateway-spi\docker-compose-shenyu-bootstrap-patch.yaml`）是**叠加**关系，两者可共存（前者改 image/删 ext-lib bind mount/加 GW_SIGN_*，后者改 networks/加 SPRING_CLOUD_*）。如同时启用，合并时注意 image 字段取 sign-spi 版本，environment 合并两组变量。

## 切换 nacos 版本

本仓库**仅保留 `nacos-2.5.3/`**（2.5.x 末版）。如需换用其他版本：

1. 复制 `nacos-2.5.3/docker-compose-nacos-2.5.3.yml` 为 `nacos-X.Y.Z/docker-compose-nacos-X.Y.Z.yml`
2. 改 `image: nacos/nacos-server:X.Y.Z`、`container_name: nacosserverXYZ`、`MYSQL_SERVICE_DB_NAME=nacos_config_XYZ`
3. 停当前：`docker stop nacosserver253`
4. 改 `docker-compose-ShenYu.yaml` 里 `SPRING_CLOUD_NACOS_DISCOVERY_SERVER-ADDR` 的容器名
5. 启新版：`docker-compose -f docker-compose-nacos-X.Y.Z.yml -p nacosXYZ up -d`
6. 重建 bootstrap：`docker stop/rm shenyu-bootstrap-261 && docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d --no-deps shenyu-bootstrap`
7. 重启 demo（新 JVM 才会重新注册）
