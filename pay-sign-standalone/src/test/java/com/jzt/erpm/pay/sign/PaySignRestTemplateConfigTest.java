package com.jzt.erpm.pay.sign;

import com.jzt.erpm.pay.sign.config.PaySignRestTemplateConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「一键产出已加签 + UTF-8 的 RestTemplate」工厂与配置（无线上调用，纯单元）.
 */
public class PaySignRestTemplateConfigTest {

    private static final String PRIVATE_KEY_RESOURCE = "keys/biz-private-key-online1.pem";

    @Test
    void factoryProducesSignedUtf8RestTemplate() throws IOException {
        PayRequestSigner signer = new PayRequestSigner(loadPrivateKey(), "06");

        // 工厂直出
        RestTemplate rt = PaySignRestTemplateFactory.createSignedUtf8(signer);

        assertTrue(PaySignRestTemplateFactory.hasPaySignInterceptor(rt),
                "工厂产出的 RestTemplate 应已注入 PaySignInterceptor");
        assertUtf8(rt);
    }

    @Test
    void configBeanIsSignedAndUtf8() throws IOException {
        // 模拟业务工程：@Import(PaySignRestTemplateConfig.class) + 注入 pay.sign.*
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.of(
                        "pay.sign.private-key", loadPrivateKey(),
                        "pay.sign.app-key", "06"
                )));
        ctx.register(PayRequestSigner.class, PaySignRestTemplateConfig.class);
        ctx.refresh();

        RestTemplate rt = ctx.getBean("paySignedRestTemplate", RestTemplate.class);
        assertNotNull(rt, "paySignedRestTemplate Bean 应被创建");
        assertTrue(PaySignRestTemplateFactory.hasPaySignInterceptor(rt),
                "配置产出的 RestTemplate 应已注入 PaySignInterceptor");
        assertUtf8(rt);

        ctx.close();
    }

    private static void assertUtf8(RestTemplate rt) {
        boolean utf8 = rt.getMessageConverters().stream()
                .filter(c -> c instanceof StringHttpMessageConverter)
                .map(c -> (StringHttpMessageConverter) c)
                .allMatch(c -> StandardCharsets.UTF_8.equals(c.getDefaultCharset()));
        assertTrue(utf8, "StringHttpMessageConverter 必须强制为 UTF-8（否则中文 body 验签失败）");
    }

    private static String loadPrivateKey() throws IOException {
        try (InputStream in = PaySignRestTemplateConfigTest.class.getClassLoader()
                .getResourceAsStream(PRIVATE_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("私钥资源未找到: " + PRIVATE_KEY_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
