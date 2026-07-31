# pay-sign-standalone

支付接口的**请求加签**与**响应验签**工具，供业务开发小组使用。

算法与 `erpm-pay-center` 一致：**SHA256withRSA + 标准 Base64**。

## 两个方向

| 方向 | 密钥 | 签名串 |
|------|------|--------|
| **请求加签**（出站） | 业务方私钥签 | 5 行：`method\nurl\ntimestamp\nnonce\nbody\n` |
| **响应/回调验签**（入站） | 业务方用支付方公钥验 | 3 行：`timestamp\nnonce\nbody\n` |

> 对称关系：出站请求由业务**私钥**加签、支付方用业务公钥验；入站响应由支付**私钥**加签、业务方用支付公钥验。

## 要求

- Java 11+
- Spring Boot 2.x（用拦截器/Bean 时需要；纯静态方法不需要）

## 项目结构

```
src/main/java/com/jzt/erpm/pay/sign/
├── PaySignUtils.java          加签/验签算法、时间戳/nonce、密钥加载（纯 JDK）
├── PaySigner.java             加签器接口
├── PaySignerImpl.java         SHA256withRSA 加签实现
├── SignResult.java            加签结果
├── PaySignInterceptor.java    RestTemplate 请求加签拦截器
├── PayRequestSigner.java      Spring Bean（@Value 注入业务私钥，请求加签）
└── PayResponseVerifier.java   响应/回调验签器（支付方公钥）

src/main/resources/keys/        示例密钥（仅供测试，生产请替换）
├── biz-private-key.pem         业务方私钥（请求加签用）
├── biz-public-key.pem          业务方公钥
├── pay-private-key.pem         支付方私钥（模拟支付方加签响应用）
└── pay-public-key.pem          支付方公钥（业务方验响应用）
```

## 请求加签

配置私钥 + 注册拦截器，RestTemplate 出站请求自动加签：

```yaml
pay:
  sign:
    private-key: |
      -----BEGIN PRIVATE KEY-----
      ...(PKCS#8 私钥)...
      -----END PRIVATE KEY-----
```

```java
@Configuration
public class RestClientConfig {
    @Bean
    public RestTemplate restTemplate(PayRequestSigner signer) {
        RestTemplate rt = new RestTemplate();
        rt.getInterceptors().add(signer.createInterceptor());
        return rt;
    }
}
```

每次请求自动注入三个头：`X-Pay-Timestamp` / `X-Pay-Nonce` / `X-Pay-Sign`。
其中 `X-Pay-Timestamp` 与 `X-Pay-Nonce` 由 `PaySignUtils.newTimestamp()` / `newNonce()` 生成。

### GET 请求的签名契约

拦截器对所有 HTTP 方法一视同仁，GET 与 POST 走同一套加签逻辑。对 GET 接口需注意：

| 签名串段 | GET 的取值 | 说明 |
|---|---|---|
| ② URL | `path + "?" + query`（**含完整 query**，解码后） | query 参数**必须**参与签名，网关侧同样用解码后的 `path?query` 验签。漏掉 query 会 401。 |
| ⑤ BODY | 空串 `""` | GET 无 body，body 段为空串。**GET 不应携带 body。** |

> ⚠️ **GET 带 body 是高危反模式**：若业务方给 GET 附带了 body，拦截器会照常把真实 body 算进签名串（与网关按真实 body 验签一致），但链路中一旦有 nginx（默认 `proxy_pass` 会剥离 GET 的 body）/ CDN / 某些负载均衡丢弃 GET body，网关读到的 body 为空，与本侧签名串第 5 行不一致 → **401 sign verify failed**。拦截器在检测到 GET 携带 body 时会打 `WARN` 日志提示。**GET 接口的参数请一律放在 query string。**

## 响应验签

收到支付方响应或异步回调时，用**支付方公钥**验签：

```java
// 启动时加载一次
PublicKey payPublicKey = PaySignUtils.loadPublicKeyResource("/keys/pay-public-key.pem");

// 收到响应后验签
boolean pass = PayResponseVerifier.verify(
        response.getHeader("X-Pay-Timestamp"),
        response.getHeader("X-Pay-Nonce"),
        responseBodyText,                      // 必须用原始报文
        response.getHeader("X-Pay-Sign"),
        payPublicKey);
```

> 验签必须用**原始报文**，反序列化后重新序列化会导致字段顺序变化而验签失败。

## 注意事项

- 报文一律 **UTF-8**。用平台默认编码（Windows GBK）会导致中文验签失败。
- 签名串每行（含最后一行）以 `\n` 结尾。
- 详细示例与密钥生成见 [USAGE.md](USAGE.md)。
