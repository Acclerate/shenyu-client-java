package com.jzt.erpm.pay.sign;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器加签回归测试：固定验证「隐患1：URL 用解码串对齐网关」与「隐患2：自动附加 X-Pay-App-Key」。
 */
class PaySignInterceptorTest {

    private static final String APP_KEY = "06";
    private static final String BODY = "{\"k\":\"v\"}";

    /**
     * 隐患1：URL 含 % 编码的中文 query，拦截器必须用「解码后」的串加签，
     * 否则网关（getPath()/getQuery() 解码后验签）算出的 URL 与加签 URL 不一致 → 401。
     */
    @Test
    void signStringMustUseDecodedUrlToMatchGateway() throws Exception {
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource("/keys/biz-private-key.pem");
        ClientHttpRequestInterceptor interceptor = new PaySignInterceptor(new PaySignerImpl(pk), APP_KEY);

        URI uri = URI.create("http://localhost/payCenter/v1/pay/url/create?name=%E6%94%AF%E4%BB%98");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, uri);
        byte[] bodyBytes = BODY.getBytes(StandardCharsets.UTF_8);

        ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        interceptor.intercept(request, bodyBytes, execution);

        // 隐患2：appKey 头应被自动附加
        assertEquals(APP_KEY, request.getHeaders().getFirst("X-Pay-App-Key"));

        // 用「网关解码后的 URL（name=支付）」重建签名串，应能验过 → 证明加签用的是解码串
        String ts = request.getHeaders().getFirst("X-Pay-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Pay-Nonce");
        String sign = request.getHeaders().getFirst("X-Pay-Sign");
        String gatewayUrl = "/payCenter/v1/pay/url/create?name=支付";
        String signString = PaySignUtils.buildRequestSignString("POST", gatewayUrl, ts, nonce, BODY);
        PublicKey pub = derivePublic(pk);
        assertTrue(PaySignUtils.verify(signString, sign, pub),
                "加签所用 URL 应与网关解码后的 URL 一致，才能验签通过（隐患1修复验证）");
    }

    /**
     * 隐患2 反向：未配置 appKey 时不附加该头，保持旧用法兼容。
     */
    @Test
    void noAppKeyHeaderWhenNotConfigured() throws Exception {
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource("/keys/biz-private-key.pem");
        ClientHttpRequestInterceptor interceptor = new PaySignInterceptor(new PaySignerImpl(pk));

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://localhost/p"));
        ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        interceptor.intercept(request, BODY.getBytes(StandardCharsets.UTF_8), execution);

        assertNull(request.getHeaders().getFirst("X-Pay-App-Key"));
    }

    private static PublicKey derivePublic(PrivateKey pk) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
        return KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }
}
