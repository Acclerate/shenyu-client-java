# shenyu-client-java

support jdk 1.8+ and spring boot 2.x

## 模块说明

- `shenyu-client-core`：客户端注册/心跳核心。包含 `sign` 包（支付加签验签工具）。
- `shenyu-sign-demo`：支付加签验签本地闭环 Demo（BIZ + PAY 双角色）。
- `shenyu-http-demo`：divide 插件验证用 HTTP 示例服务。

## 支付加签验签（微信支付 V3 风格）

客户端签名工具位于 `shenyu-client-core` 的 `org.apache.shenyu.client.core.sign` 包：

| 类 | 职责 |
|----|------|
| `SignConstants` | 协议常量（`X-Pay-*` 头 / SHA256withRSA / `\n` 分隔符） |
| `PemUtils` | PEM 密钥加载（PKCS#8 私钥 / X.509 公钥） |
| `SignStringBuilder` | 待签名串构造（请求 5 行 / 响应 3 行） |
| `RsaSigner` | SHA256withRSA 加签验签 |
| `PaySignInterceptor` | OkHttp 出站请求自动加签 |
| `PaySignVerifier` | 响应验签 / 回调验签 / 入站验签 + 防重放 |

网关侧 SignPlugin + RequestPlugin + DividePlugin 协作详见 `sign-plugin-验证手册.md`。

### 运行 Demo

```bash
cd shenyu-sign-demo
mvn spring-boot:run

# 请求加签→请求验签→响应加签→响应验签
curl http://localhost:8390/biz/pay

# 回调通知加签→回调验签
curl -X POST http://localhost:8390/v3/pay/notify-trigger
```

Demo 密钥位于 `shenyu-sign-demo/src/main/resources/keys/`，仅供本地验证，禁止生产使用。
