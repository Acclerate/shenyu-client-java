package com.jzt.erpm.pay.sign;

import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PayResponseVerifier 单元测试.
 *
 * <p>用「支付私钥签响应 → 支付公钥验响应」闭环，
 * 模拟业务方收到支付服务响应后的验签场景。
 */
class PayResponseVerifierTest {

    /** 正常响应：支付私钥签 → 业务方支付公钥验，应通过. */
    @Test
    void shouldPassForValidSignature() {
        PrivateKey payPrivateKey = PaySignUtils.loadPrivateKeyResource("/keys/pay-private-key.pem");
        PublicKey payPublicKey = PaySignUtils.loadPublicKeyResource("/keys/pay-public-key.pem");

        String timestamp = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String body = "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";
        // 模拟支付服务用支付私钥对响应加签（3 行串）
        String sign = PaySignUtils.sign(
                PaySignUtils.buildResponseSignString(timestamp, nonce, body), payPrivateKey);

        boolean pass = PayResponseVerifier.verify(timestamp, nonce, body, sign, payPublicKey);

        assertTrue(pass, "支付私钥签的响应，支付公钥应验签通过");
    }
}
