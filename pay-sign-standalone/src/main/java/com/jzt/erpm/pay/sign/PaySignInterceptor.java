package com.jzt.erpm.pay.sign;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 支付请求签名拦截器.
 *
 * <p>自动为 RestTemplate 请求添加支付签名头：
 * <ul>
 *   <li>{@code X-Pay-Timestamp} - 请求时间戳</li>
 *   <li>{@code X-Pay-Nonce} - 请求随机串</li>
 *   <li>{@code X-Pay-Sign} - 签名值</li>
 *   <li>{@code X-Pay-App-Key} - 业务方 appKey（仅当构造时传入 appKey 才附加；网关要求必传）</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 *   &#64;Configuration
 *   public class RestClientConfig {
 *       &#64;Bean
 *       public RestTemplate restTemplate(PaySigner paySigner) {
 *           RestTemplate restTemplate = new RestTemplate();
 *           restTemplate.getInterceptors().add(new PaySignInterceptor(paySigner));
 *           return restTemplate;
 *       }
 *   }
 * </pre>
 *
 * <p>签名串格式（5行）：
 * <pre>
 * HTTP请求方法\n
 * URL\n
 * 请求时间戳\n
 * 请求随机串\n
 * 请求报文主体\n
 * </pre>
 *
 * @author 黄华
 * @since 1.0.0
 */
public class PaySignInterceptor implements ClientHttpRequestInterceptor {

    private static final Log LOG = LogFactory.getLog(PaySignInterceptor.class);

    private static final String HEADER_TIMESTAMP = "X-Pay-Timestamp";
    private static final String HEADER_NONCE = "X-Pay-Nonce";
    private static final String HEADER_SIGN = "X-Pay-Sign";
    private static final String HEADER_APP_KEY = "X-Pay-App-Key";

    private final PaySigner paySigner;
    private final String appKey;

    /**
     * 创建拦截器（不携带 appKey；如需网关校验 appKey，请改用 {@link #PaySignInterceptor(PaySigner, String)}）.
     *
     * @param paySigner 签名器实现
     */
    public PaySignInterceptor(PaySigner paySigner) {
        this(paySigner, null);
    }

    /**
     * 使用指定签名器创建拦截器，并自动为请求附加 X-Pay-App-Key 头.
     *
     * <p>网关 {@code PayRsaSignService#validateHeaders} 要求 X-Pay-App-Key 必传，
     * 否则返回 {@code 401 missing X-Pay-App-Key header}。业务侧若通过配置注入了 appKey，
     * 由本拦截器统一附加，避免业务方每次手写该头。
     *
     * @param paySigner 签名器实现
     * @param appKey    业务方 appKey（如 06）；为 null 或空则不附加该头（兼容旧用法）
     */
    public PaySignInterceptor(PaySigner paySigner, String appKey) {
        if (paySigner == null) {
            throw new IllegalStateException("paySigner不能为空");
        }
        this.paySigner = paySigner;
        this.appKey = (appKey == null || appKey.trim().isEmpty()) ? null : appKey.trim();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String method = request.getMethod().name();
        // 与网关 PayRsaSignService 对齐：网关用 getPath()/getQuery()（已解码 %XX），
        // 此处必须同样使用解码后的路径/query；若用 getRawPath()/getRawQuery()，
        // 当 URL 含中文或 % 编码字符时，加签的 URL 与网关验签的 URL 不一致 → 401 sign verify failed。
        String url = request.getURI().getPath();
        if (request.getURI().getQuery() != null) {
            url = url + "?" + request.getURI().getQuery();
        }
        String timestamp = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        // 必须显式 UTF-8：验签侧(PaySignUtils.sign / erpm)一律 UTF-8，
        // 用平台默认编码会在 Windows(GBK) 下导致中文 body 签名串不一致 → 我方验签失败
        String bodyStr = new String(body, StandardCharsets.UTF_8);

        // GET 带 body 是高危反模式：本拦截器会照常把真实 body 算进签名串（与网关侧按真实 body 验签一致），
        // 但链路中一旦有 nginx(默认 proxy_pass 剥离 GET body)/CDN/某些 LB 丢弃 GET body，
        // 网关读到的 body 将为空 → 与本侧签名串第5行不一致 → 401 sign verify failed。
        // HTTP/1.1 RFC 7231 §4.3.1 虽允许 GET 带 body 但语义未定义，此处仅告警不阻断（避免误伤合法场景）。
        if (HttpMethod.GET.name().equalsIgnoreCase(method) && body != null && body.length > 0) {
            LOG.warn("GET 请求携带了 " + body.length + " 字节的 body 并将参与签名。"
                    + "若链路中存在 nginx/CDN 等会剥离 GET body 的组件，网关侧读到空 body 将导致 401 sign verify failed。"
                    + "建议 GET 请求不要带 body（参数请放 query string）。URL=" + url);
        }

        SignResult signResult = paySigner.sign(method, url, timestamp, nonce, bodyStr);

        request.getHeaders().set(HEADER_TIMESTAMP, signResult.getTimestamp());
        request.getHeaders().set(HEADER_NONCE, signResult.getNonce());
        request.getHeaders().set(HEADER_SIGN, signResult.getSign());
        if (appKey != null) {
            request.getHeaders().set(HEADER_APP_KEY, appKey);
        }

        return execution.execute(request, body);
    }
}
