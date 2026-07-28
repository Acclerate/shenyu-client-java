# 使用指南

## 一、密钥生成（SHA256withRSA）

本工具签名算法为 **SHA256withRSA**，密钥格式为 PEM：

- 私钥：PKCS#8，`-----BEGIN PRIVATE KEY-----` 开头
- 公钥：X.509，`-----BEGIN PUBLIC KEY-----` 开头

推荐 **RSA-2048**（强度足够、兼容性好）。用 OpenSSL 生成：

> OpenSSL：Linux/macOS 自带或可安装；Windows 可用 Git Bash，或安装 OpenSSL 后在 CMD/PowerShell 里用。

**1. 生成 RSA-2048 私钥（PEM）**

```bash
openssl genpkey -algorithm RSA -out private_key.pem -pkeyopt rsa_keygen_bits:2048
# 或者
openssl genrsa -out private_key.pem 2048
```

> `genpkey` 生成的是 PKCS#8 格式（`-----BEGIN PRIVATE KEY-----`），本工具直接可用。
> `genrsa` 生成的是 PKCS#1 格式（`-----BEGIN RSA PRIVATE KEY-----`），需要用下一条命令转换后再用。

**2. 从私钥导出公钥**

```bash
openssl rsa -pubout -in private_key.pem -out public_key.pem
```

**3. 查看密钥内容**

```bash
cat private_key.pem
cat public_key.pem
```

本项目内置了 4 把示例密钥（`src/main/resources/keys/`，仅供测试）：

| 文件 | 角色 |
|------|------|
| `biz-private-key.pem` | 业务方私钥（请求加签） |
| `biz-public-key.pem` | 业务方公钥 |
| `pay-private-key.pem` | 支付方私钥（模拟支付方加签响应） |
| `pay-public-key.pem` | 支付方公钥（业务方验响应） |

生产环境请用上述 OpenSSL 命令自行生成并替换，**示例密钥切勿上生产**。

## 二、请求加签

### 1. 配置私钥

```yaml
pay:
  sign:
    app-key: "06"            # 业务方 appKey，自动附加为 X-Pay-App-Key 头（网关要求必传）
    private-key: |
      -----BEGIN PRIVATE KEY-----
      （PKCS#8 私钥内容）
      -----END PRIVATE KEY-----
```

### 2. 注册 RestTemplate 拦截器（⚠️ 必须 UTF-8）

> **关键坑**：`RestTemplate` 的 `StringHttpMessageConverter` 默认编码是 **ISO-8859-1**。
> 请求体含中文（买家名、商品描述）时若未显式设为 UTF-8，写出字节会被损坏，
> 导致网关 `401 sign verify failed`。因此「加签」与「UTF-8」必须成对出现。

#### ✅ 推荐：一键工厂产出（已含 UTF-8，业务系统零配置）

```java
// 方式一：直接用工厂新建
@Bean
public RestTemplate restTemplate(PayRequestSigner signer) {
    return PaySignRestTemplateFactory.createSignedUtf8(signer);
}

// 方式二：复用业务自有 RestTemplate（不破坏其连接池等配置）
@Bean
public RestTemplate restTemplate(PayRequestSigner signer, SomeConfig cfg) {
    RestTemplate rt = new RestTemplate(cfg.customRequestFactory());
    PaySignRestTemplateFactory.configureSignedUtf8(rt, signer);   // 注入加签 + UTF-8
    return rt;
}
```

#### ✅ 更省事：直接导入「一键 Bean」配置

```java
@Configuration
@Import(PaySignRestTemplateConfig.class)   // 启用后自动产出 bean 名 paySignedRestTemplate
public class PayConfig { }

// 使用：
@Autowired
@Qualifier("paySignedRestTemplate")
private RestTemplate payClient;
```

> `PaySignRestTemplateConfig` 位于 `com.jzt.erpm.pay.sign.config` 子包，**不会被**
> 对 `com.jzt.erpm.pay.sign` 的组件扫描自动加载，需显式 `@Import` 或扫描该子包，
> 避免与业务自有 `RestTemplate` Bean 冲突。

#### ⚠️ 若手写（不推荐）：务必补上 UTF-8

```java
@Bean
public RestTemplate restTemplate(PayRequestSigner signer) {
    RestTemplate rt = new RestTemplate();
    rt.getMessageConverters().stream()
        .filter(c -> c instanceof StringHttpMessageConverter)
        .forEach(c -> ((StringHttpMessageConverter) c).setDefaultCharset(StandardCharsets.UTF_8));
    rt.getInterceptors().add(signer.createInterceptor());
    return rt;
}
```

### 3. 发起请求（自动加签）

```java
@Autowired
private RestTemplate restTemplate;

String resp = restTemplate.postForObject(url, request, String.class);
```

自动注入头：

| 头 | 取值 | 生成方式 |
|----|------|----------|
| `X-Pay-Timestamp` | 时间戳字符串 | `PaySignUtils.newTimestamp()`（毫秒级） |
| `X-Pay-Nonce` | 32 位 hex | `PaySignUtils.newNonce()`（`SecureRandom`） |
| `X-Pay-Sign` | Base64 签名 | SHA256withRSA 私钥签 |
| `X-Pay-App-Key` | `pay.sign.app-key` 配置值 | 配置了才附加（网关要求必传） |

## 三、响应验签

收到支付方响应或异步回调，用**支付方公钥**验签：

```java
PublicKey payPublicKey = PaySignUtils.loadPublicKeyResource("/keys/pay-public-key.pem");

boolean pass = PayResponseVerifier.verify(
        response.getHeader("X-Pay-Timestamp"),
        response.getHeader("X-Pay-Nonce"),
        responseBodyText,                  // 原始报文
        response.getHeader("X-Pay-Sign"),
        payPublicKey);

// 可选：防重放（±5 分钟）
boolean fresh = PayResponseVerifier.checkTimestamp(
        response.getHeader("X-Pay-Timestamp"), 300_000);
```

## 四、纯静态用法（不用 Spring）

```java
PrivateKey sk = PaySignUtils.loadPrivateKeyResource("/keys/biz-private-key.pem");

// 加签（时间戳/nonce 由工具类生成）
String timestamp = PaySignUtils.newTimestamp();
String nonce = PaySignUtils.newNonce();
String signString = PaySignUtils.buildRequestSignString(
        "POST", "/v3/pay/transactions/jsapi", timestamp, nonce, bodyJson);
String sign = PaySignUtils.sign(signString, sk);
```

## 五、签名串格式

签名串由 `PaySignUtils.buildRequestSignString` / `buildResponseSignString` 构造，
**时间戳和 nonce 在生产代码、测试代码中均由 `PaySignUtils.newTimestamp()` / `newNonce()` 生成**，下方示例值仅为示意。

**请求（5 行）：**
```
POST\n
/v3/pay/transactions/jsapi\n
1700000000000\n
593bec0c930bf1afeb40b4a08c8fb242\n
{"code":"SUCCESS"}\n
```

**响应/回调（3 行）：**
```
1700000000000\n
593bec0c930bf1afeb40b4a08c8fb242\n
{"code":"SUCCESS"}\n
```

每行（含最后一行）以 `\n` 结尾；GET 请求 body 为空仍保留末尾 `\n`。

## 六、常见问题

| 报错 | 原因 |
|------|------|
| `pay.sign.private-key未配置` | yml 未配 `pay.sign.private-key` |
| `pay.sign.private-key格式错误` | 私钥被截断 / 非 PKCS#8 / 换行异常 |
| 对方验签失败 | 编码非 UTF-8 / URL 不含 query / 方法未大写 / 换行符缺失 / 密钥不配对 |
| `401 missing X-Pay-App-Key header` | 未配置 `pay.sign.app-key`（或构造拦截器未传 appKey），网关要求该头必传 |

## 七、核心类

| 类 | 说明 |
|----|------|
| `PaySignUtils` | 加签/验签算法、时间戳/nonce、密钥加载（纯 JDK） |
| `PaySignerImpl` | SHA256withRSA 加签实现 |
| `PaySignInterceptor` | RestTemplate 拦截器，自动加签 |
| `PayRequestSigner` | Spring Bean，读取私钥配置 |
| `PaySignRestTemplateFactory` | 一键产出「已加签 + UTF-8」的 RestTemplate（业务系统推荐） |
| `PaySignRestTemplateConfig` | `@Configuration` 一键产出 `paySignedRestTemplate` Bean（opt-in，需 `@Import`） |
| `PayResponseVerifier` | 响应/回调验签（支付公钥） |

## 八、测试用例

测试类位于 `src/test/java/com/jzt/erpm/pay/sign/`，各只保留一个核心用例，
加签/验签输入的时间戳、nonce 均由 `PaySignUtils` 生成，与生产逻辑一致：

| 测试类 | 用例 | 验证点 |
|--------|------|--------|
| `PaySignerTest` | `shouldSignWithFiveLineFormat` | `PaySignerImpl` 产出的 5 行签名串格式正确，时间戳/nonce 原样回传 |
| `PaySignInterceptorTest` | `signStringMustUseDecodedUrlToMatchGateway` | 拦截器用解码后 URL 对齐网关，并自动附加 X-Pay-App-Key 头 |
| `PaySignRestTemplateConfigTest` | `factoryProducesSignedUtf8RestTemplate` / `configBeanIsSignedAndUtf8` | 工厂与配置产出的 RestTemplate 已注入 PaySignInterceptor 且 StringHttpMessageConverter 为 UTF-8 |
| `GatewaySignViaInterceptorTest` | `callLiveGatewayViaProductionSigner` | 走生产加签代码（PayRequestSigner+工厂+RestTemplate）调线上网关，HTTP 200 验签通过 |
| `GatewaySignCallExampleTest` | `shouldSignAndCallGateway` | 手动 OkHttp+PaySignUtils 调线上网关，HTTP 200 验签通过（基础范例） |
| `PayResponseVerifierTest` | `shouldPassForValidSignature` | 支付私钥签的响应，支付公钥验签通过（加签→验签闭环） |

运行：

```bash
cd pay-sign-standalone
mvn test
```
