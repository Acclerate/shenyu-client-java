package com.jzt.erpm.pay.sign;

import java.security.PublicKey;

/**
 * 支付响应验签器（业务方侧）.
 *
 * <p>业务方收到支付服务的<b>响应</b>或<b>异步回调</b>时，用<b>支付方公钥</b>验签，
 * 确认该响应/回调确由支付服务发出且未被篡改。
 *
 * <p>签名串为 3 行（与 erpm {@code PayResponseSignAdvice} 加签格式对偶）：
 * <pre>
 * 应答时间戳\n
 * 应答随机串\n
 * 应答报文主体\n
 * </pre>
 * 每行（含最后一行）以 {@code \n} 结尾。报文主体为空时仍保留末尾 {@code \n}。
 *
 * <p>与请求加签的对称关系：
 * <ul>
 *   <li>出站请求：业务<b>私钥</b>加签 → 支付方用业务公钥验签（见 {@link PayRequestSigner}）</li>
 *   <li>入站响应：支付<b>私钥</b>加签 → 业务方用支付公钥验签（本类）</li>
 * </ul>
 *
 * <p>纯 JDK 实现，不依赖 Spring，回调入口/RestTemplate 响应处理均可直接调用。
 *
 * <h3>用法（响应）</h3>
 * <pre>
 *   // 加载支付方公钥（启动时一次）
 *   PublicKey payPublicKey = PaySignUtils.loadPublicKeyResource("/keys/pay-public-key.pem");
 *
 *   // 收到响应后验签
 *   boolean pass = PayResponseVerifier.verify(
 *           response.getHeader("X-Pay-Timestamp"),
 *           response.getHeader("X-Pay-Nonce"),
 *           responseBodyText,                          // 原始报文主体，框架不得篡改
 *           response.getHeader("X-Pay-Sign"),
 *           payPublicKey);
 *   if (!pass) {
 *       // 验签失败：丢弃响应/回调，记录告警
 *   }
 * </pre>
 *
 * <h3>用法（异步回调）</h3>
 * <pre>
 *   boolean pass = PayResponseVerifier.verify(timestamp, nonce, rawBody, sign, payPublicKey);
 * </pre>
 *
 * @see PaySignUtils#buildResponseSignString(String, String, String)
 * @see PaySignUtils#verify(String, String, PublicKey)
 */
public final class PayResponseVerifier {

    /** HTTP 头：应答/回调时间戳. */
    public static final String HEADER_TIMESTAMP = "X-Pay-Timestamp";

    /** HTTP 头：应答/回调随机串. */
    public static final String HEADER_NONCE = "X-Pay-Nonce";

    /** HTTP 头：签名值. */
    public static final String HEADER_SIGN = "X-Pay-Sign";

    private PayResponseVerifier() {
    }

    /**
     * 验证支付服务的响应/回调签名.
     *
     * <p>内部构造 3 行签名串 {@code timestamp\nnonce\nbody\n}，用支付方公钥做 SHA256withRSA 验签。
     *
     * @param timestamp   应答/回调时间戳（来自 X-Pay-Timestamp 头）
     * @param nonce       应答/回调随机串（来自 X-Pay-Nonce 头）
     * @param body        应答/回调报文主体<b>原文</b>（务必使用原始报文，序列化框架不得篡改；
     *                    为 {@code null} 视为空串）
     * @param base64Sign  签名值（来自 X-Pay-Sign 头，Base64 编码）
     * @param payPublicKey 支付方公钥
     * @return {@code true} 验签通过；{@code false} 签名不匹配
     * @throws IllegalStateException 任一参数缺失导致无法构造/验签，或签名值非法
     */
    public static boolean verify(String timestamp, String nonce, String body,
                                 String base64Sign, PublicKey payPublicKey) {
        if (timestamp == null) {
            throw new IllegalStateException("timestamp不能为空（缺少 X-Pay-Timestamp 头）");
        }
        if (nonce == null) {
            throw new IllegalStateException("nonce不能为空（缺少 X-Pay-Nonce 头）");
        }
        if (base64Sign == null) {
            throw new IllegalStateException("sign不能为空（缺少 X-Pay-Sign 头）");
        }
        String signString = PaySignUtils.buildResponseSignString(timestamp, nonce, body);
        return PaySignUtils.verify(signString, base64Sign, payPublicKey);
    }

    /**
     * 时间戳时效校验（防重放），响应/回调均可使用.
     *
     * <p>时间戳与 {@link PaySignUtils#newTimestamp()} 一致，为<b>毫秒级</b>字符串；
     * 允许偏差也以毫秒为单位，内部直接比较、无需换算。
     *
     * @param timestamp      毫秒级时间戳字符串
     * @param toleranceMillis 允许偏差毫秒数（建议 300_000，即 5 分钟）
     * @return {@code true} 在允许偏差内；格式非法返回 {@code false}
     */
    public static boolean checkTimestamp(String timestamp, long toleranceMillis) {
        try {
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            return Math.abs(now - ts) <= toleranceMillis;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
