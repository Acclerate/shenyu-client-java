package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.api.VerifyResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PayRsaSignService 防重放检查单测（mock guard，不依赖 Redis）。
 *
 * <p>覆盖：合法 nonce 放行并标记、重放 nonce → 401、replayGuard 为 null 兼容旧行为、
 * 以及 spec 校验顺序（replay 在 sign 之前）——签名失败前仍触达 replayGuard。
 */
class PayRsaSignServiceReplayTest {

    private static final String APP_KEY = "06";

    private static final String PATH = "/payCenter/v1/pay/url/create";

    private static KeyPair keyPair;

    private PayReplayGuard replayGuard;

    private PayRsaSignService service;

    @BeforeAll
    static void genKey() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        replayGuard = mock(PayReplayGuard.class);
        when(replayGuard.getProps()).thenReturn(PayReplayProperties.of(
                true, "redis://ignored", 330, 500L, true, 30_000L));
        // replayGuard 注入，隔离 replay 行为
        service = new PayRsaSignService(appKey -> keyPair.getPublic(), replayGuard);
    }

    // ====== 用真实 RSA 签名构造合法请求（验签必须先通过，才会走到 in-flight） ======

    private ServerWebExchange signedExchange(final String ts, final String nonce, final String body,
                                             final ServerHttpResponse response) throws Exception {
        final String signString = "POST\n" + PATH + "\n" + ts + "\n" + nonce + "\n" + body + "\n";
        final Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signString.getBytes(StandardCharsets.UTF_8));
        final String sign = Base64.getEncoder().encodeToString(signer.sign());

        final HttpHeaders headers = new HttpHeaders();
        headers.add("X-Pay-Timestamp", ts);
        headers.add("X-Pay-Nonce", nonce);
        headers.add("X-Pay-Sign", sign);
        headers.add("X-Pay-App-Key", APP_KEY);

        final ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethodValue()).thenReturn("POST");
        when(request.getURI()).thenReturn(URI.create("http://localhost:9195" + PATH));

        final ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);
        return exchange;
    }

    @Test
    void freshNoncePassesAndMarks() throws Exception {
        final String ts = String.valueOf(System.currentTimeMillis());
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        final String expectedKey = "replay:06:" + ts + ":" + nonce;
        when(replayGuard.tryMark(expectedKey)).thenReturn(true);
        final ServerHttpResponse response = mock(ServerHttpResponse.class);

        final String body = "{\"requestSerialNo\":\"SN001\",\"bizOrderNo\":\"BIZ001\",\"amount\":100}";
        final VerifyResult result = service.signatureVerify(signedExchange(ts, nonce, body, response), body);

        assertTrue(result.isSuccess());
        verify(replayGuard).tryMark(expectedKey);
    }

    @Test
    void replayedNonceReturns401() throws Exception {
        final String ts = String.valueOf(System.currentTimeMillis());
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        when(replayGuard.tryMark(anyString())).thenReturn(false);
        final ServerHttpResponse response = mock(ServerHttpResponse.class);

        final String body = "{\"requestSerialNo\":\"SN001\",\"bizOrderNo\":\"BIZ001\",\"amount\":100}";
        final VerifyResult result = service.signatureVerify(signedExchange(ts, nonce, body, response), body);

        assertFalse(result.isSuccess());
        verify(replayGuard).tryMark(anyString());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replayGuardNullKeepsLegacyBehavior() throws Exception {
        service = new PayRsaSignService(appKey -> keyPair.getPublic(), null);
        final String ts = String.valueOf(System.currentTimeMillis());
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        final ServerHttpResponse response = mock(ServerHttpResponse.class);

        final String body = "{\"requestSerialNo\":\"SN001\",\"bizOrderNo\":\"BIZ001\",\"amount\":100}";
        final VerifyResult result = service.signatureVerify(signedExchange(ts, nonce, body, response), body);

        assertTrue(result.isSuccess());
    }

    @Test
    void invalidSignatureStillTouchesReplayGuardFirst() throws Exception {
        // spec 顺序：replay 在 sign 之前。即便签名最终失败，replayCheck 先执行。
        final String ts = String.valueOf(System.currentTimeMillis());
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        when(replayGuard.tryMark(anyString())).thenReturn(true);
        final ServerHttpResponse response = mock(ServerHttpResponse.class);

        final String body = "{\"requestSerialNo\":\"SN001\",\"bizOrderNo\":\"BIZ001\",\"amount\":100}";
        final ServerWebExchange exchange = signedExchange(ts, nonce, body, response);
        // 篡改 body：验签必失败
        final VerifyResult result = service.signatureVerify(exchange, body + " ");

        assertFalse(result.isSuccess());
        // 关键：replay 检查发生在签名验证之前，故仍触达 replayGuard
        verify(replayGuard, atLeastOnce()).tryMark(anyString());
        verify(response, atLeastOnce()).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
