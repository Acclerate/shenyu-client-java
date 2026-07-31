package com.jzt.erpm.pay.sign;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用「项目生产加签代码」({@link PayRequestSigner} + {@link PaySignInterceptor} + RestTemplate)
 * 直接调用线上网关的示例测试。
 *
 * <p>与 {@link GatewaySignCallExampleTest}（手动 OkHttp + PaySignUtils）不同，本测试完全走
 * 业务系统推荐的生产集成路径：Spring 注入 {@link PayRequestSigner} → createInterceptor() 注入
 * RestTemplate → 自动加签 + 自动附加 X-Pay-App-Key。用于验证：
 * <ul>
 *   <li>修复后拦截器自动附加 X-Pay-App-Key（不再 401 missing X-Pay-App-Key header）</li>
 *   <li>修复后拦截器用解码 URL 与网关对齐（避免中文/% 编码接口 401 sign verify failed）</li>
 *   <li>与网关验签闭环：返回 200（哪怕后端业务异常 99999999，也代表签名已过）</li>
 * </ul>
 *
 * <p>⚠️ 本测试命中真实线上网关，需本机能直连内网域名 shenyu.dev.jzterp.net。
 */
public class GatewaySignViaInterceptorTest {

    private static final String GATEWAY_URL = "https://shenyu.dev.jztweb.com/payCenter/v1/pay/url/create";

    /** 与网关验签通过的字节保持一致的请求体（中文需 UTF-8 发送） */
    private static final String BODY =
            "{\n"
            + "  \"requestSerialNo\" : \"FDGDSD20260000600002\",\n"
            + "  \"bizOrderNo\" : \"FDGDSD202600006\",\n"
            + "  \"goodsDesc\" : \"上下游辅助系统：代收单\",\n"
            + "  \"amount\" : 50,\n"
            + "  \"buyerName\" : \"黄金梅利\",\n"
            + "  \"buyerUniqueId\" : \"0000032183G00001\",\n"
            + "  \"makerName\" : \"黄金梅利\"\n"
            + "}";

    /** appKey=06 对应的生产私钥（classpath 下） */
    private static final String PRIVATE_KEY_RESOURCE = "keys/biz-private-key-online-06.pem";

    private static final String APP_KEY = "06";

    @Test
    void callLiveGatewayViaProductionSigner() throws IOException {
        // 1) 从 classpath 读取生产私钥（Spring 配置 pay.sign.private-key 的等价来源）
        String privateKeyPem = loadPrivateKeyFromClasspath();

        // 2) 构建最小 Spring 容器，把私钥 + appKey 注入 PayRequestSigner（与业务工程一致）
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.of(
                        "pay.sign.private-key", privateKeyPem,
                        "pay.sign.app-key", APP_KEY
                )));
        ctx.register(PayRequestSigner.class);
        ctx.refresh();

        PayRequestSigner signer = ctx.getBean(PayRequestSigner.class);
        assertNotNull(signer, "PayRequestSigner bean 应被 Spring 成功创建");

        // 3) 用工厂一键产出「自动加签 + UTF-8」的 RestTemplate（业务系统推荐用法，无需手配编码）
        RestTemplate restTemplate = PaySignRestTemplateFactory.createSignedUtf8(signer);

        // 4) 发起线上网关请求（不手动 set 任何签名头，全部由拦截器自动完成）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/json; charset=utf-8"));
        HttpEntity<String> entity = new HttpEntity<>(BODY, headers);

        System.out.println("============================================================");
        System.out.println("  生产加签代码调用线上网关（PayRequestSigner + RestTemplate）");
        System.out.println("============================================================");
        System.out.println("请求 URL : " + GATEWAY_URL);
        System.out.println("请求体   :\n" + BODY);

        org.springframework.http.ResponseEntity<String> resp =
                restTemplate.postForEntity(GATEWAY_URL, entity, String.class);

        int status = resp.getStatusCode().value();
        String body = resp.getBody();
        System.out.println("------------------------------------------------------------");
        System.out.println("HTTP 状态 : " + status);
        System.out.println("响应体   : " + body);
        System.out.println("------------------------------------------------------------");

        // 5) 断言：签名/头部被网关接受（非 401 签名类错误即视为验签通过）
        assertTrue(status != HttpStatus.UNAUTHORIZED.value()
                        || (!body.contains("sign verify failed")
                            && !body.contains("missing X-Pay-App-Key header")
                            && !body.contains("timestamp expired")),
                "网关返回了签名类 401，说明 PayRequestSigner/PaySignInterceptor 加签与网关不一致: " + body);

        // 200 + 99999999（后端业务异常）也是「签名已过」的证据；401 其它非签名原因需人工排查
        System.out.println(status == HttpStatus.OK.value()
                ? "✅ 验签通过（网关接受签名；99999999 为后端业务异常，与签名无关）"
                : "⚠️ 非 200：非签名类问题（如网络/网关路由/业务拦截），但签名未被拒");

        ctx.close();
    }

    private static String loadPrivateKeyFromClasspath() throws IOException {
        try (InputStream in = GatewaySignViaInterceptorTest.class.getClassLoader()
                .getResourceAsStream(PRIVATE_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("私钥资源未找到: " + PRIVATE_KEY_RESOURCE
                        + "（请确认 pay-sign-standalone/src/main/resources/keys/biz-private-key-online-06.pem 存在）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
