# shenyu-admin-appauth-spi 部署说明

> 模块：`shenyu-admin-appauth-spi`（admin 侧 app_auth 扩展 SPI）
> 版本：`2.6.1`
> SPI 源码：`D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi`
> 部署目标：`apache/shenyu-admin:2.6.1` 镜像，通过 ext-lib 加载
>
> 配套文档：
> - SPI 逻辑说明：`README.md`
> - 设计背景：`../shenyu-sign-gateway-spi/sign-2.6.1-appauth-实时公钥源设计方案.md`

---

## 1. 构建前置：安装 shenyu-admin 到本地 Maven 仓库

`shenyu-admin:2.6.1` 不是中央仓库发布的 artifact（它是可执行应用），需先从 ShenYu 源码 `mvn install` 到本地仓库，扩展 jar 才能编译。

> 已在开发机执行过则可跳过。新机器/CI 首次构建必须做。

```bash
# ShenYu 源码位置
cd D:\privategit\github\shenyu

# 1. 先安装 listener-api（admin 依赖它）
mvn -pl shenyu-admin-listener/shenyu-admin-listener-api install -DskipTests

# 2. 安装 admin 主模块（会拉取依赖；若遇 grpc-api:1.53.0 缺失，见下方排错）
mvn -pl shenyu-admin install -DskipTests

# 验证
ls ~/.m2/repository/org/apache/shenyu/shenyu-admin/2.6.1/
# 应看到 shenyu-admin-2.6.1.jar 和 .pom
```

**排错：`Could not find artifact io.grpc:grpc-api:jar:1.53.0`**

阿里云镜像没有该版本，需从中央仓库拉一次，并清除 `_remote.repositories` 仓库来源标记（否则 Maven 会因来源不匹配拒绝使用）：

```bash
# 从中央仓库拉取 grpc 全家桶
mvn dependency:get -Dartifact=io.grpc:grpc-api:1.53.0 -DremoteRepositories=https://repo1.maven.org/maven2

# 清除来源标记，让本地缓存可被任意项目使用
find ~/.m2/repository/io/grpc -name "_remote.repositories" -delete

# 重新安装 admin（离线模式，避免再次联网校验）
cd D:\privategit\github\shenyu
mvn -pl shenyu-admin install -DskipTests -o
```

---

## 2. 打 SPI jar

```bash
cd D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi

mvn clean package
# 产物：target/shenyu-admin-appauth-spi-2.6.1.jar （约 11KB 薄 jar）
```

**产物校验**：jar 应只含 5 个自定义类 + spring.factories，无任何外部依赖类：

```bash
jar tf target/shenyu-admin-appauth-spi-2.6.1.jar | grep "\.class$"
# 预期输出 5 行：
#   org/apache/shenyu/admin/custom/AppAuthCustomConfiguration.class
#   org/apache/shenyu/admin/custom/AppAuthCustomCreateController.class
#   org/apache/shenyu/admin/custom/AppAuthCustomCreateService.class
#   org/apache/shenyu/admin/custom/dto/CustomAppAuthCreateReq.class
#   org/apache/shenyu/admin/custom/dto/CustomAppAuthCreateResp.class
```

---

## 3. 部署到 shenyu-admin

admin 官方镜像 `apache/shenyu-admin:2.6.1` 的 `entrypoint.sh` 把 `/opt/shenyu-admin/ext-lib/*` 拼进 JVM 启动 classpath，SPI jar 放进该目录即被加载。两种落地方式：

### 方式A：bind mount ext-lib（当前 mysql-connector 的做法，最简单）

> 适用于现有 docker-compose 部署。admin 服务已挂载 `./shenyu-admin/ext-lib`，直接把 jar 丢进去即可，**无需重建镜像**。

```bash
# 把 SPI jar 放进已挂载的 ext-lib 目录（与 mysql-connector.jar 并列）
cp D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi\target\shenyu-admin-appauth-spi-2.6.1.jar \
   D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-admin\ext-lib\

# 重启 admin（compose 目录）
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1
docker compose -f docker-compose-ShenYu.yaml restart shenyu-admin
```

**验证**：

```bash
# 1. 启动日志应能看到 SPI 装配（AppAuthCustomConfiguration 被 Spring 扫描）
docker logs shenyu-admin-261 2>&1 | grep -i "custom\|appauth-spi"

# 2. 调用接口验证（需先登录拿 token）
TOKEN=$(curl -s "http://localhost:9096/platform/login?userName=admin&password=1qaz!QAZ" | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -X POST "http://localhost:9096/appAuth/customCreate" \
  -H "X-Access-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"appKey":"TEST_SPI","appSecret":"-----BEGIN PUBLIC KEY-----\nMIIBIjAN\n-----END PUBLIC KEY-----","enabled":true,"open":false}'
# 预期：{"code":200,"message":null,"data":{"id":"xxx","appKey":"TEST_SPI"}}

# 3. 查 DB 确认记录写入（app_key 用了入参指定值，非随机）
docker exec mysql57 mysql -uroot -p<pwd> -e "SELECT app_key, LEFT(app_secret,30) FROM shenyu_261.app_auth WHERE app_key='TEST_SPI'"
```

### 方式B：镜像化（K8s 友好，与 bootstrap SPI 一致）

> 适用于 K8s 部署或消除 bind mount 依赖的场景。新建 admin Dockerfile，把 SPI jar 烤进镜像。

**1. 准备 Dockerfile**（放在 compose 目录的 `shenyu-admin/` 下）：

```dockerfile
# 文件：D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-admin\Dockerfile
FROM apache/shenyu-admin:2.6.1

LABEL maintainer="shenyu-client-java"
LABEL description="ShenYu 2.6.1 admin with appauth-spi (custom appKey create)"

# SPI jar 烤进镜像 ext-lib（mysql-connector 仍走 bind mount，二者互不影响）
COPY ext-lib/shenyu-admin-appauth-spi-2.6.1.jar /opt/shenyu-admin/ext-lib/
```

**2. 暂存 jar 到构建上下文**：

```bash
cp D:\privategit\github\shenyu-client-java\shenyu-admin-appauth-spi\target\shenyu-admin-appauth-spi-2.6.1.jar \
   D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-admin\ext-lib\
```

**3. 构建镜像**：

```bash
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1\shenyu-admin
docker build -t shenyu-admin:2.6.1-appauth-spi .
```

**4. 修改 docker-compose 引用新镜像**：

```yaml
# docker-compose-ShenYu.yaml
services:
  shenyu-admin:
    image: shenyu-admin:2.6.1-appauth-spi   # 改为自定义镜像
    container_name: shenyu-admin-261
    # volumes 保持不变；ext-lib bind mount 仍提供 mysql-connector，
    # SPI jar 已在镜像里，bind mount 里的同名文件不会覆盖（镜像层在底层）
    volumes:
      - "./shenyu-admin/conf:/opt/shenyu-admin/conf"
      - "./shenyu-admin/logs:/opt/shenyu-admin/logs"
      - "./shenyu-admin/ext-lib:/opt/shenyu-admin/ext-lib"
    # ... 其余不变
```

```bash
cd D:\privategit\gitee\docker-compose\Windows\shenyu-2.6.1
docker compose -f docker-compose-ShenYu.yaml up -d shenyu-admin
```

验证同方式A。

---

## 4. ext-lib 加载机制（原理说明）

admin 官方镜像 `entrypoint.sh`（`/opt/shenyu-admin/entrypoint.sh`）关键两行：

```bash
EXT_LIB=${DEPLOY_DIR}/ext-lib
CLASS_PATH=.:${DEPLOY_DIR}/conf:${DEPLOY_DIR}/lib/*:${EXT_LIB}/*
exec ${JAVA_HOME}/bin/java ${JAVA_OPTS} -classpath ${CLASS_PATH} org.apache.shenyu.admin.ShenyuAdminBootstrap
```

- `ext-lib/*` 与 `lib/*` 同级拼进 JVM 启动 classpath（**非** Spring Boot loader.path 机制）
- ext-lib 下的 jar 被 admin 主类加载器加载，与 admin 自身类同 classloader
- SPI 的 `META-INF/spring.factories` 由 Spring Boot 自动装配扫描，`AppAuthCustomConfiguration` 被实例化，注册 Controller 和 Service Bean
- 注入的 `AppAuthMapper`、`ApplicationEventPublisher` 是 admin 容器既有 Bean，直接复用

**与 bootstrap SPI 的差异**：bootstrap 用 ShenyuLoaderService（独立 classloader），admin 用传统 `-classpath`（同一 classloader）。本 SPI 无类隔离需求，传统 classpath 更简单可靠。

---

## 5. 回滚

| 方式 | 回滚操作 |
|---|---|
| A（bind mount） | 删除 `shenyu-admin/ext-lib/shenyu-admin-appauth-spi-2.6.1.jar` → `docker compose restart shenyu-admin`。原生 `/appAuth/*` 接口不受影响 |
| B（镜像化） | docker-compose 的 `image` 改回 `apache/shenyu-admin:2.6.1` → `docker compose up -d shenyu-admin` |

回滚后 `POST /appAuth/customCreate` 端点消失，erpm-pay-center 调用会 404。app_auth 表已写入的记录保留（不影响原生功能）。

---

## 6. 与 erpm-pay-center 的对接（后续任务）

SPI 部署验收后，erpm-pay-center 的 `ShenyuKeyPushService` 改造：把推送目标从 `/plugin` 改为 `/appAuth/customCreate`，复用现有 token 机制。

```
字段映射：
  appKey   ← pay_app_config.app_key
  appSecret← pay_app_config.app_public_key（normalizeToPem 后的 PEM）
  enabled  ← true
  open     ← false
```

此部分在 erpm-pay-center 仓库独立完成，本文档不展开。
