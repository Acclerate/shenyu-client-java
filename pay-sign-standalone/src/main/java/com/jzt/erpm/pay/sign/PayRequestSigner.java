package com.jzt.erpm.pay.sign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;

/**
 * 支付请求加签器（Spring Bean 风格）.
 *
 * <ul>
 *   <li>{@code @Component}：被业务工程的 Spring 容器扫描，直接 {@code @Autowired} 注入即可</li>
 *   <li>从配置 {@code pay.sign.private-key} 读取 PEM 私钥字符串</li>
 *   <li>构造时解析私钥，线程安全</li>
 *   <li>提供 {@link #createInterceptor()} 创建自动加签拦截器（注入 RestTemplate）</li>
 * </ul>
 *
 * <p>仅在业务工程引入了 spring-context 时生效（pom 中 spring-context 为 optional）。
 *
 * <h3>业务工程配置示例</h3>
 * <pre>
 * # application.yml
 * pay:
 *   sign:
 *     private-key: |
 *       -----BEGIN PRIVATE KEY-----
 *       ...(PKCS#8 私钥内容)...
 *       -----END PRIVATE KEY-----
 * </pre>
 *
 * <h3>业务工程使用示例</h3>
 * <pre>
 *   &#64;Configuration
 *   public class RestClientConfig {
 *       &#64;Bean
 *       public RestTemplate restTemplate(PayRequestSigner payRequestSigner) {
 *           RestTemplate restTemplate = new RestTemplate();
 *           restTemplate.getInterceptors().add(payRequestSigner.createInterceptor());
 *           return restTemplate;
 *       }
 *   }
 * </pre>
 */
@Component
public class PayRequestSigner {

    private final PrivateKey privateKey;

    /**
     * 从配置 {@code pay.sign.private-key} 读取 PEM 私钥字符串并解析.
     *
     * @param privateKey PEM 私钥字符串（含 BEGIN/END 标记，或纯 Base64）
     */
    public PayRequestSigner(@Value("${pay.sign.private-key:}") String privateKey) {
        this.privateKey = PaySignUtils.loadPrivateKey(privateKey);
    }

    /**
     * 创建请求签名拦截器，用于自动为 RestTemplate 请求添加签名头.
     *
     * <p>拦截器会自动完成：
     * <ul>
     *   <li>生成时间戳和随机串</li>
     *   <li>构造 5 行签名串</li>
     *   <li>使用 SHA256withRSA 签名</li>
     *   <li>设置 HTTP 头：X-Pay-Timestamp、X-Pay-Nonce、X-Pay-Sign</li>
     * </ul>
     *
     * @return 请求签名拦截器
     */
    public ClientHttpRequestInterceptor createInterceptor() {
        return new PaySignInterceptor(new PaySignerImpl(privateKey));
    }
}
