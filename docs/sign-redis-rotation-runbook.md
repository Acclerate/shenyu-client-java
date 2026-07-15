# 公钥轮换运维手册（Redis-backed Public Key Rotation Runbook）

> 适用场景：BIZ 端业务系统公钥定期轮换（如每年一次），需要更新网关侧 Redis 中的 `biz-public-key.pem`。
> 推荐方案：versioned-key 灰度轮换，避免全局瞬时失败。

---

## 一、前置检查

### 1.1 检查当前 Redis 中的公钥指纹

```bash
docker exec shenyu-redis redis-cli --raw GET shenyu:sign:biz-public-key.pem \
  | openssl rsa -pubin -outform DER 2>/dev/null \
  | openssl dgst -sha256 -hex | awk '{print $NF}'
```

输出示例：`a1b2c3d4e5f6...`

### 1.2 检查当前版本

```bash
docker exec shenyu-redis redis-cli GET shenyu:sign:biz-public-key.pem.version
```

输出：`v1` 或 `(nil)`（未设置版本标识）

---

## 二、生成新密钥对

### 2.1 在 BIZ 端生成新私钥 + 公钥

```bash
# 生成 2048-bit RSA 私钥
openssl genrsa -out biz-private-key-v2.pem 2048

# 导出公钥
openssl rsa -in biz-private-key-v2.pem -pubout -out biz-public-key-v2.pem

# 校验公钥
openssl rsa -pubin -in biz-public-key-v2.pem -text -noout | head -20
```

### 2.2 计算新公钥指纹

```bash
openssl rsa -pubin -in biz-public-key-v2.pem -outform DER \
  | openssl dgst -sha256 -hex | awk '{print $NF}'
```

输出示例：`9876543210abcdef...`（与旧公钥不同）

---

## 三、灰度轮换流程（推荐）

### 3.1 写入 versioned-key

**方案**：不直接覆盖 `shenyu:sign:biz-public-key.pem`，而是写入带版本号的 key：

```bash
# 写入 v2 公钥
docker exec -i shenyu-redis redis-cli -x SET shenyu:sign:biz-public-key.pem.v2 \
  < biz-public-key-v2.pem

# 设置版本标识（可选，用于审计）
docker exec shenyu-redis redis-cli SET shenyu:sign:biz-public-key.pem.version v2

# 验证 v2 写入成功
docker exec shenyu-redis redis-cli --no-raw GET shenyu:sign:biz-public-key.pem.v2 \
  | diff - biz-public-key-v2.pem
```

### 3.2 单网关实例灰度

**目标**：在 1 台网关实例上切换到 v2，验证无误后全量切换。

修改单台实例的环境变量（Docker Compose 或 K8s）：

```yaml
# docker-compose.yaml（仅修改 1 个副本，或副本数=1 时直接改）
environment:
  - GW_SIGN_REDIS_BIZ_PUBLIC_KEY_KEY=shenyu:sign:biz-public-key.pem.v2
```

重启该实例：

```bash
docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d shenyu-bootstrap
```

### 3.3 验证灰度实例

**检查日志**：

```bash
docker-compose logs -f shenyu-bootstrap | grep "GW-Sign"
```

预期：`[GW-Sign] 公钥源已启用 Redis 模式 ... key=shenyu:sign:biz-public-key.pem.v2`

**发起验签测试**：

```bash
# BIZ 端用新私钥签名发起请求（参考 docs/sign-plugin-验证手册.md 第九章）
curl -X POST http://localhost:8391/biz/pay/...
```

预期：网关验签通过（`[GW-Sign] ✅ 验签通过`）

### 3.4 全量切换

灰度验证通过后，对所有网关实例应用 v2：

**Docker Compose**（单机多副本或扩容）：
```yaml
environment:
  - GW_SIGN_REDIS_BIZ_PUBLIC_KEY_KEY=shenyu:sign:biz-public-key.pem.v2
```

```bash
docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d
```

**Kubernetes**（多副本滚动更新）：
```yaml
# Deployment env 修改
env:
  - name: GW_SIGN_REDIS_BIZ_PUBLIC_KEY_KEY
    value: shenyu:sign:biz-public-key.pem.v2
```

```bash
kubectl rollout restart deployment/shenyu-bootstrap
```

### 3.5 通知业务方

BIZ 端开始用新私钥签名发布新版本客户端：

- BIZ 私钥替换为 `biz-private-key-v2.pem`
- PAY 端无需改动（PAY 仍用 BIZ 公钥验签响应）
- 灰度发布新版本 BIZ 客户端

### 3.6 清理旧版本

确认全量切换 + BIZ 新版本发布后 7 天，删除旧 key：

```bash
docker exec shenyu-redis redis-cli DEL shenyu:sign:biz-public-key.pem.v1
```

---

## 四、简化轮换流程（直接覆盖）

> 适用场景：单机部署、停机窗口可接受、风险可控。

### 4.1 直接覆盖主 key

```bash
# 写入新公钥（覆盖旧值）
docker exec -i shenyu-redis redis-cli -x SET shenyu:sign:biz-public-key.pem \
  < biz-public-key-v2.pem
```

### 4.2 等待缓存过期

网关本地缓存 TTL 默认 30s，**最多等待 30s + 刷新周期**后自动生效。

### 4.3 BIZ 端切换私钥

网关缓存过期后，BIZ 端用新私钥签名即可。

**缺点**：30s 内会出现验签失败（BIZ 用新私钥签名，网关仍用旧公钥验签）。

---

## 五、回滚流程

### 5.1 灰度回滚（切换回 v1）

```bash
# 环境变量改回 v1
- GW_SIGN_REDIS_BIZ_PUBLIC_KEY_KEY=shenyu:sign:biz-public-key.pem.v1

docker-compose -f docker-compose-ShenYu.yaml -p shenyu261 up -d shenyu-bootstrap
```

### 5.2 直接覆盖回滚

```bash
# 从备份恢复旧公钥
docker exec -i shenyu-redis redis-cli -x SET shenyu:sign:biz-public-key.pem \
  < biz-public-key-v1.pem
```

### 5.3 BIZ 端回滚

BIZ 端回退到使用旧私钥的版本。

---

## 六、应急场景处理

### 6.1 Redis 故障

**现象**：`[GW-Sign] Redis 重连失败`

**行为**：网关自动降级到 classpath PEM（jar 内的默认公钥）

**恢复**：Redis 恢复后，网关 60s 内自动切回 Redis 源

**验证**：
```bash
docker logs shenyu-bootstrap | grep "Redis 已恢复"
```

### 6.2 BIZ 客户端版本回退

**场景**：BIZ 端已用新私钥签名，但网关仍用旧公钥

**临时方案**：延长 `GW_SIGN_CACHE_TTL_SECONDS`（如改为 86400 = 1 天），强制使用旧公钥，等待 BIZ 回退

**永久方案**：BIZ 端回退到旧版本客户端

### 6.3 公钥文件损坏

**现象**：`[GW-Sign] 刷新业务公钥失败，继续使用旧缓存` + 验签持续失败

**排查**：
```bash
# 检查 Redis 中 PEM 是否有效
docker exec shenyu-redis redis-cli --no-raw GET shenyu:sign:biz-public-key.pem | head -5
```

预期应看到：
```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQ...
```

若输出非 PEM 格式，重新写入正确 PEM。

---

## 七、审计记录

每次轮换应记录以下信息到 wiki / CMDB：

| 字段 | 示例值 |
|---|---|
| 日期 | 2026-07-02 |
| 操作人 | 张三 |
| 旧版本指纹 | a1b2c3d4e5f6... |
| 新版本指纹 | 9876543210abcdef... |
| Redis key | `shenyu:sign:biz-public-key.pem.v2` |
| BIZ 私钥版本 | v2 |
| 灰度实例 | shenyu-bootstrap-1 |
| 全量切换时间 | 2026-07-02 10:30:00 UTC |
| BIZ 新版本发布时间 | 2026-07-02 11:00:00 UTC |
| 旧 key 删除时间 | 2026-07-09 00:00:00 UTC |
| 备注 | 无异常 |

---

## 八、多租户扩展（未来工作）

当前 `BizPublicKeyProvider` 接口仅 `PublicKey currentKey()`，不支持多租户。

未来扩展方向：
```java
public interface BizPublicKeyProvider {
    PublicKey currentKey() throws Exception;          // 单租户（当前）
    PublicKey keyFor(String appKey) throws Exception; // 多租户（预留）
}
```

实现时可在 Redis 中存储：
```
shenyu:sign:biz-public-key:biz-app-a-v2.pem
shenyu:sign:biz-public-key:biz-app-b-v1.pem
```

网关从请求头 `X-Pay-App-Key` 取 appKey 路由到对应公钥。
