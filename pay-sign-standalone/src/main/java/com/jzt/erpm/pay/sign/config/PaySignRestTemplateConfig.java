package com.jzt.erpm.pay.sign.config;

import com.jzt.erpm.pay.sign.PayRequestSigner;
import com.jzt.erpm.pay.sign.PaySignRestTemplateFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 支付加签 RestTemplate 的「一键 Bean」配置（业务系统 opt-in 启用）.
 *
 * <p>本配置位于 {@code com.jzt.erpm.pay.sign.config} 子包，<b>不会</b>被业务工程对
 * {@code com.jzt.erpm.pay.sign} 的组件扫描自动加载 —— 避免与业务自有 {@code RestTemplate} Bean 冲突。
 * 业务系统按需启用：
 * <pre>
 *   &#64;Configuration
 *   &#64;Import(PaySignRestTemplateConfig.class)   // 或把本包纳入组件扫描
 *   public class YourConfig { ... }
 * </pre>
 *
 * <p>启用后直接注入使用（bean 名 {@code paySignedRestTemplate}，与常见的 {@code restTemplate} 区分）：
 * <pre>
 *   &#64;Autowired
 *   &#64;Qualifier("paySignedRestTemplate")
 *   private RestTemplate payClient;
 * </pre>
 *
 * <p>产出的 RestTemplate 已自动：① 强制 UTF-8 编码（中文 body 验签必须）② 注入 PaySignInterceptor（自动加签 + 自动附 X-Pay-App-Key）。
 */
@Configuration
public class PaySignRestTemplateConfig {

    /**
     * 一键产出「已加签 + UTF-8」的 RestTemplate Bean.
     *
     * @param signer 支付请求加签器（Spring 注入，读取 pay.sign.private-key / pay.sign.app-key）
     * @return 可直接调用支付网关的 RestTemplate
     */
    @Bean
    public RestTemplate paySignedRestTemplate(PayRequestSigner signer) {
        return PaySignRestTemplateFactory.createSignedUtf8(signer);
    }
}
