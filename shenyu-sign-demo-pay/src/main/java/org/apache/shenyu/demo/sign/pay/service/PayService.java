/*
 * PAY 侧核心逻辑：验签入站请求 + 用私钥加签响应/回调。
 * 从原 demo 迁移，逻辑不变。
 */
package org.apache.shenyu.demo.sign.pay.service;

import org.apache.shenyu.client.core.sign.PaySignVerifier;
import org.apache.shenyu.client.core.sign.RsaSigner;
import org.apache.shenyu.client.core.sign.SignConstants;
import org.apache.shenyu.client.core.sign.SignStringBuilder;
import org.apache.shenyu.demo.sign.pay.config.PaySignProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PayService.
 *
 * <p>支付服务侧核心逻辑：验签业务系统入站请求 + 用私钥加签响应/回调。
 *
 * <p>注意：阶段3（网关侧 RSA 验签 SPI）上线后，入站请求验签上移到网关 SignPlugin，
 * 此处的 verifyBizRequest 可保留作双保险，或移除（看需求）。
 */
@Service
public class PayService {

    private static final Logger LOG = LoggerFactory.getLogger(PayService.class);

    private final PrivateKey payPrivateKey;

    private final PublicKey bizPublicKey;

    private final PaySignProperties properties;

    public PayService(@Qualifier("payPrivateKey") final PrivateKey payPrivateKey,
                      @Qualifier("bizPublicKey") final PublicKey bizPublicKey,
                      final PaySignProperties properties) {
        this.payPrivateKey = payPrivateKey;
        this.bizPublicKey = bizPublicKey;
        this.properties = properties;
    }

    /**
     * 验签业务系统入站请求（支付服务侧）。
     *
     * <p>注意：经网关转发后，PAY 收到的 URI 已被 ContextPathPlugin 剥离 contextPath 前缀。
     * 阶段2（PAY 侧验签）时，如果 BIZ 加签用的是带 contextPath 的路径，此处会验签失败。
     * 阶段3（网关侧验签）上线后，验签在网关 SignPlugin 用原始路径完成，此处可跳过。
     *
     * @param request HTTP 请求
     * @return 验签结果
     * @throws IOException 读取请求体失败
     */
    public VerifyOutcome verifyBizRequest(final HttpServletRequest request) throws IOException {
        // 阶段3：网关 SignPlugin 已用原始路径（含 contextPath）完成验签，请求到达 PAY 即被信任。
        // 此处跳过验签（网关 ContextPathPlugin 已剥离 contextPath，PAY 侧重算签名串路径不一致）。
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String url = (query == null) ? uri : uri + "?" + query;
        String body = readBody(request);
        String sign = request.getHeader(SignConstants.X_PAY_SIGN);
        LOG.info("[PAY] ====== 收到请求 ======");
        LOG.info("[PAY] 入站请求 | method={} url={} bodyLen={} bodyPreview={}", method, url, body.length(),
                body.length() > 100 ? body.substring(0, 100) + "..." : body);
        LOG.info("[PAY] 收到签名头 | X-Pay-Sign={}", sign == null ? "无" : sign.substring(0, Math.min(40, sign.length())) + "...");

        if (!properties.isSignVerifyEnabled()) {
            LOG.info("[PAY] 验签已关闭（信任网关 SignPlugin），直接放行");
            return new VerifyOutcome(true, true, null, null, body);
        }
        String timestamp = request.getHeader(SignConstants.X_PAY_TIMESTAMP);
        String nonce = request.getHeader(SignConstants.X_PAY_NONCE);

        boolean tsOk = PaySignVerifier.checkTimestamp(timestamp, properties.getTimestampToleranceSeconds());
        boolean signOk;
        if (timestamp == null || nonce == null || sign == null) {
            signOk = false;
        } else {
            String signString = SignStringBuilder.buildRequestSignString(method, url, timestamp, nonce, body);
            LOG.info("[PAY] 待验签名串(5行)={}", signString.replace("\n", "↩"));
            signOk = RsaSigner.verify(signString, sign, bizPublicKey);
        }
        LOG.info("[PAY] 验签结果 | tsOk={} signOk={}", tsOk, signOk);
        return new VerifyOutcome(signOk, tsOk, timestamp, nonce, body);
    }

    /**
     * 用支付服务私钥对响应体加签，返回需写入响应头的签名信息。
     *
     * @param body 响应报文主体
     * @return 签名头信息（timestamp / nonce / sign）
     */
    public Map<String, String> signResponse(final String body) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signString = SignStringBuilder.buildResponseSignString(timestamp, nonce, body);
        String sign = RsaSigner.sign(signString, payPrivateKey);
        Map<String, String> headers = new HashMap<>();
        headers.put(SignConstants.X_PAY_TIMESTAMP, timestamp);
        headers.put(SignConstants.X_PAY_NONCE, nonce);
        headers.put(SignConstants.X_PAY_SIGN, sign);
        LOG.info("[PAY] ====== 响应加签（支付私钥加密）======");
        LOG.info("[PAY] 响应体 | {}", body);
        LOG.info("[PAY] 待签名串(3行)={}", signString.replace("\n", "↩"));
        LOG.info("[PAY] 签名值 | ts={} nonce={} sign={}", timestamp, nonce, sign);
        return headers;
    }

    /**
     * 构造回调通知（用支付服务私钥加签），返回通知体 + 签名头。
     *
     * @param tradeNo 交易号
     * @return 通知体与签名头
     */
    public NotifyPayload buildNotify(final String tradeNo) {
        String body = "{\"trade_no\":\"" + tradeNo + "\",\"status\":\"SUCCESS\",\"paid_at\":"
                + System.currentTimeMillis() + "}";
        Map<String, String> headers = signResponse(body);
        return new NotifyPayload(body, headers);
    }

    private String readBody(final HttpServletRequest request) throws IOException {
        byte[] bytes;
        try (java.io.InputStream in = request.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            bytes = out.toByteArray();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * VerifyOutcome.
     */
    public static final class VerifyOutcome {

        private final boolean signPass;

        private final boolean timestampPass;

        private final String timestamp;

        private final String nonce;

        private final String body;

        VerifyOutcome(final boolean signPass, final boolean timestampPass,
                      final String timestamp, final String nonce, final String body) {
            this.signPass = signPass;
            this.timestampPass = timestampPass;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.body = body;
        }

        public boolean isSignPass() {
            return signPass;
        }

        public boolean isTimestampPass() {
            return timestampPass;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getNonce() {
            return nonce;
        }

        public String getBody() {
            return body;
        }
    }

    /**
     * NotifyPayload.
     */
    public static final class NotifyPayload {

        private final String body;

        private final Map<String, String> headers;

        NotifyPayload(final String body, final Map<String, String> headers) {
            this.body = body;
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }
    }
}
