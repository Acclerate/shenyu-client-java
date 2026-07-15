package com.jzt.erpm.pay.sign;

import java.security.PrivateKey;

/**
 * 支付签名器实现（SHA256withRSA）.
 *
 * <p>签名串格式（5行）：
 * <pre>
 * HTTP请求方法\n
 * URL\n
 * 请求时间戳\n
 * 请求随机串\n
 * 请求报文主体\n
 * </pre>
 *
 * @since 1.0.0
 */
public class PaySignerImpl implements PaySigner {

    private final PrivateKey privateKey;

    /**
     * 使用指定私钥创建签名器.
     *
     * @param privateKey 业务系统私钥（PKCS#8 格式）
     */
    public PaySignerImpl(PrivateKey privateKey) {
        if (privateKey == null) {
            throw new IllegalStateException("privateKey不能为空");
        }
        this.privateKey = privateKey;
    }

    @Override
    public SignResult sign(String method, String url, String timestamp,
                           String nonce, String body) {
        String signString = PaySignUtils.buildRequestSignString(method, url, timestamp, nonce, body);
        String signValue = PaySignUtils.sign(signString, privateKey);
        return new SignResult(timestamp, nonce, signValue, signString);
    }
}
