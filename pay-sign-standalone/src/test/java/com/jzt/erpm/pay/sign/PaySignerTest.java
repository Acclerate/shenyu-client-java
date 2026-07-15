package com.jzt.erpm.pay.sign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 支付加签组件单元测试.
 */
class PaySignerTest {

    private static final String CLASSPATH_PRIVATE_KEY = "/keys/biz-private-key.pem";

    /** 加签 验证5行签名串格式、时间戳/随机串传递 */
    @Test
    void shouldSignWithFiveLineFormat() {
        PaySigner signer = new PaySignerImpl(
                PaySignUtils.loadPrivateKeyResource(CLASSPATH_PRIVATE_KEY));

        String timestamp = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String body = "{\"appid\":\"wxd678efh567hg6787\"}";

        SignResult result = signer.sign(
                "POST", "/v3/pay/transactions/jsapi", timestamp, nonce, body);

        assertNotNull(result);
        assertEquals(timestamp, result.getTimestamp());
        assertEquals(nonce, result.getNonce());
        assertNotNull(result.getSign());
        assertEquals(
                PaySignUtils.buildRequestSignString(
                        "POST", "/v3/pay/transactions/jsapi", timestamp, nonce, body),
                result.getSignString());
    }
}
