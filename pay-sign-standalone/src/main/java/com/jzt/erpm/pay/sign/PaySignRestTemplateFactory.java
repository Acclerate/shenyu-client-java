package com.jzt.erpm.pay.sign;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 支付加签 RestTemplate 工厂（业务系统一键产出「已加签 + UTF-8」的 RestTemplate）.
 *
 * <h3>为什么需要它</h3>
 * <p>RestTemplate 的 {@link StringHttpMessageConverter} 默认编码是 <b>ISO-8859-1</b>。
 * 当请求体含中文（如支付单的买家名、商品描述）时，若未显式设为 UTF-8，则：
 * <ul>
 *   <li>写出字节 = ISO-8859-1 损坏的中文</li>
 *   <li>拦截器用 {@code new String(body, UTF_8)} 签出的摘要 ≠ 网关 UTF-8 解码出的字节</li>
 *   <li>→ 网关 {@code 401 sign verify failed}</li>
 * </ul>
 * 因此「加签 + UTF-8」必须成对出现。本工厂把这两步固化，业务系统无需再手配。
 *
 * <h3>用法</h3>
 * <pre>
 * // 方式一：直接新建
 * RestTemplate rt = PaySignRestTemplateFactory.createSignedUtf8(payRequestSigner);
 *
 * // 方式二：给已有 RestTemplate 注入加签 + UTF-8（复用业务自有连接池等配置）
 * PaySignRestTemplateFactory.configureSignedUtf8(existingRestTemplate, payRequestSigner);
 *
 * // 方式三：用 @Configuration 一键产出 Bean（见 PaySignRestTemplateConfig）
 * &#64;Import(PaySignRestTemplateConfig.class)
 * </pre>
 *
 * @author 黄华
 * @since 1.0.0
 */
public final class PaySignRestTemplateFactory {

    private PaySignRestTemplateFactory() {
    }

    /**
     * 新建一个已注入「签名拦截器 + UTF-8 编码」的 RestTemplate.
     *
     * @param signer 支付请求加签器（由 Spring 注入，读取 pay.sign.private-key / pay.sign.app-key）
     * @return 可直接调用支付网关的 RestTemplate（自动带 X-Pay-Timestamp/Nonce/Sign/App-Key）
     */
    public static RestTemplate createSignedUtf8(PayRequestSigner signer) {
        RestTemplate restTemplate = new RestTemplate();
        applyUtf8(restTemplate);
        restTemplate.getInterceptors().add(signer.createInterceptor());
        return restTemplate;
    }

    /**
     * 新建一个已注入「签名拦截器 + UTF-8 编码」的 RestTemplate（底层变体）.
     *
     * @param paySigner 签名器实现
     * @param appKey    业务方 appKey（如 06）；传 null 则不附加 X-Pay-App-Key 头
     * @return 可直接调用支付网关的 RestTemplate
     */
    public static RestTemplate createSignedUtf8(PaySigner paySigner, String appKey) {
        RestTemplate restTemplate = new RestTemplate();
        applyUtf8(restTemplate);
        restTemplate.getInterceptors().add(new PaySignInterceptor(paySigner, appKey));
        return restTemplate;
    }

    /**
     * 给已有的 RestTemplate 注入「签名拦截器 + UTF-8 编码」（不覆盖其原有拦截器/转换器之外的配置）.
     *
     * @param restTemplate 业务系统已有的 RestTemplate
     * @param signer       支付请求加签器
     */
    public static void configureSignedUtf8(RestTemplate restTemplate, PayRequestSigner signer) {
        applyUtf8(restTemplate);
        restTemplate.getInterceptors().add(signer.createInterceptor());
    }

    private static void applyUtf8(RestTemplate restTemplate) {
        for (HttpMessageConverter<?> converter : restTemplate.getMessageConverters()) {
            if (converter instanceof StringHttpMessageConverter) {
                ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }

    /** 仅用于断言/调试：判断 RestTemplate 是否已注入 PaySignInterceptor */
    static boolean hasPaySignInterceptor(RestTemplate restTemplate) {
        for (ClientHttpRequestInterceptor interceptor : restTemplate.getInterceptors()) {
            if (interceptor instanceof PaySignInterceptor) {
                return true;
            }
        }
        return false;
    }
}
