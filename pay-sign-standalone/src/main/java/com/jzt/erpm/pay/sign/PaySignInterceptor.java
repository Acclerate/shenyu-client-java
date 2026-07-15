package com.jzt.erpm.pay.sign;

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

    private static final String HEADER_TIMESTAMP = "X-Pay-Timestamp";
    private static final String HEADER_NONCE = "X-Pay-Nonce";
    private static final String HEADER_SIGN = "X-Pay-Sign";

    private final PaySigner paySigner;

    /**
     * 使用指定签名器创建拦截器.
     *
     * @param paySigner 签名器实现
     */
    public PaySignInterceptor(PaySigner paySigner) {
        if (paySigner == null) {
            throw new IllegalStateException("paySigner不能为空");
        }
        this.paySigner = paySigner;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String method = request.getMethod().name();
        String url = request.getURI().getRawPath();
        if (request.getURI().getRawQuery() != null) {
            url = url + "?" + request.getURI().getRawQuery();
        }
        String timestamp = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        // 必须显式 UTF-8：验签侧(PaySignUtils.sign / erpm)一律 UTF-8，
        // 用平台默认编码会在 Windows(GBK) 下导致中文 body 签名串不一致 → 我方验签失败
        String bodyStr = new String(body, StandardCharsets.UTF_8);

        SignResult signResult = paySigner.sign(method, url, timestamp, nonce, bodyStr);

        request.getHeaders().set(HEADER_TIMESTAMP, signResult.getTimestamp());
        request.getHeaders().set(HEADER_NONCE, signResult.getNonce());
        request.getHeaders().set(HEADER_SIGN, signResult.getSign());

        return execution.execute(request, body);
    }
}
