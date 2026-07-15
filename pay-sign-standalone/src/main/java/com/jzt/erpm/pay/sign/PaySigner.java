package com.jzt.erpm.pay.sign;

/**
 * 支付签名器接口.
 *
 * <p>默认提供 {@link PaySignerImpl} 实现，使用 SHA256withRSA 算法。
 *
 * @since 1.0.0
 */
public interface PaySigner {

    /**
     * 对请求进行签名.
     *
     * @param method    HTTP 请求方法（GET/POST 等）
     * @param url       请求 URL 绝对路径（不含 scheme/host）
     * @param timestamp 请求时间戳（毫秒级字符串）
     * @param nonce     请求随机串
     * @param body      请求报文主体（GET 传空串）
     * @return 签名结果，包含时间戳、随机串和签名值
     */
    SignResult sign(String method, String url, String timestamp,
                    String nonce, String body);
}
