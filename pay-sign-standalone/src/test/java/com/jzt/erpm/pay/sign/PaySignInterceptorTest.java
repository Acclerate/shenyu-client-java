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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器加签回归测试：固定验证「隐患1：URL 用解码串对齐网关」与「隐患2：自动附加 X-Pay-App-Key」，
 * 以及「GET 请求签名串契约」（query 进 URL、无 body 时 body 段为空、带 body 时按真实 body 加签）。
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

    /**
     * GET 回归1（正向）：GET 无 body 时，签名串必须满足
     * <ul>
     *   <li>第 2 行 URL 包含完整 query（与网关 {@code getPath()+"?"+getQuery()} 对齐）</li>
     *   <li>第 5 行 body 段为空串（GET 无 body，RestTemplate 传入 {@code byte[0]}）</li>
     * </ul>
     * 否则网关验签必 401。本用例固定守护「GET 的 query 进 URL、body 段为空」这一契约。
     */
    @Test
    void getWithoutBodySignsUrlWithQueryAndEmptyBody() throws Exception {
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource("/keys/biz-private-key.pem");
        ClientHttpRequestInterceptor interceptor = new PaySignInterceptor(new PaySignerImpl(pk), APP_KEY);

        // GET 带 query，无 body（RestTemplate 发 GET 时拦截器收到的 body 为空字节数组）
        URI uri = URI.create("http://localhost/payCenter/v1/pay/query?bizOrderNo=FDGDSD202600006&page=1");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        byte[] emptyBody = new byte[0];
        ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        interceptor.intercept(request, emptyBody, execution);

        String ts = request.getHeaders().getFirst("X-Pay-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Pay-Nonce");
        String sign = request.getHeaders().getFirst("X-Pay-Sign");

        // 守护点1：URL 必须含完整 query（解码后），与网关 buildRequestUrl 一致
        String expectedUrl = "/payCenter/v1/pay/query?bizOrderNo=FDGDSD202600006&page=1";
        // 守护点2：body 段为空串，与网关 rewriteRequestBody 对 GET 无 body 的 switchIfEmpty("") 一致
        String signString = PaySignUtils.buildRequestSignString("GET", expectedUrl, ts, nonce, "");

        PublicKey pub = derivePublic(pk);
        assertTrue(PaySignUtils.verify(signString, sign, pub),
                "GET 无 body 时，签名串 URL 应含 query、body 段应为空串，否则网关验签失败");
    }

    /**
     * GET 回归2（风险佐证）：GET 携带非空 body 时，拦截器仍会把真实 body 算进签名串第 5 行。
     * <p>这是高危反模式：一旦链路中 nginx/CDN 剥离 GET body，网关读到空 body，
     * 与本侧签名串第 5 行（真实 body）不一致 → 401。本用例固化此行为，确保：
     * <ul>
     *   <li>加签时 body 段确实用了真实 body（而非被强制清空）——与网关侧按真实 body 验签的契约一致</li>
     *   <li>该路径有显式测试覆盖，将来任何改动都会被本用例挡住</li>
     * </ul>
     */
    @Test
    void getWithBodyIncludesRealBodyInSignString() throws Exception {
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource("/keys/biz-private-key.pem");
        ClientHttpRequestInterceptor interceptor = new PaySignInterceptor(new PaySignerImpl(pk), APP_KEY);

        URI uri = URI.create("http://localhost/payCenter/v1/pay/query?bizOrderNo=FDGDSD202600006");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        byte[] getBody = BODY.getBytes(StandardCharsets.UTF_8); // 故意给 GET 带上非空 body
        ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        interceptor.intercept(request, getBody, execution);

        String ts = request.getHeaders().getFirst("X-Pay-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Pay-Nonce");
        String sign = request.getHeaders().getFirst("X-Pay-Sign");
        String expectedUrl = "/payCenter/v1/pay/query?bizOrderNo=FDGDSD202600006";

        // 用「真实 body」重建签名串应能验过 → 证明 GET 带 body 时 body 段确实用了真实 body（而非空串）
        String signStringWithRealBody = PaySignUtils.buildRequestSignString(
                "GET", expectedUrl, ts, nonce, BODY);
        PublicKey pub = derivePublic(pk);
        assertTrue(PaySignUtils.verify(signStringWithRealBody, sign, pub),
                "GET 带 body 时，加签 body 段应使用真实 body（与网关侧按真实 body 验签的契约一致）");

        // 反证：若误用「空 body」重建，必验不过 → 证明 body 段不是空的
        String signStringWithEmptyBody = PaySignUtils.buildRequestSignString(
                "GET", expectedUrl, ts, nonce, "");
        assertFalse(PaySignUtils.verify(signStringWithEmptyBody, sign, pub),
                "GET 带 body 时，body 段不是空串；若此处验过说明加签逻辑被错误改动");
    }

    private static PublicKey derivePublic(PrivateKey pk) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
        return KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }
}
