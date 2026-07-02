/*
 * PayRsaSignService —— 解法A 的核心实现。
 *
 * 解法A关键点：网关侧验签，用 exchange.getRequest().getURI().getPath() 取网关收到的原始路径
 * （带 contextPath，如 /pay-demo/v3/pay/transactions/jsapi）。
 * 此时 SignPlugin(order=50) 在 ContextPathPlugin(order=150) 之前执行，路径还没被剥离，
 * 与 BIZ 加签时用的路径完全一致，天然匹配。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.api.VerifyResult;
import org.apache.shenyu.plugin.sign.service.SignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * PayRsaSignService.
 *
 * <p>微信支付 V3 风格 RSA-SHA256 验签（ShenYu 2.6.1 SignService 实现）。
 * 用业务系统公钥验签入站请求的 X-Pay-Sign。
 *
 * <p>签名串格式（5 行，每行 \n 结尾）：
 * <pre>
 * HTTP请求方法\n
 * URL\n                    ← 网关收到的原始路径（带 contextPath），GET 含 query string
 * 请求时间戳\n
 * 请求随机串\n
 * 请求报文主体\n
 * </pre>
 *
 * <p>与客户端 org.apache.shenyu.client.core.sign.SignStringBuilder.buildRequestSignString 的逻辑一致。
 */
public class PayRsaSignService implements SignService {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignService.class);

    private static final String X_PAY_TIMESTAMP = "X-Pay-Timestamp";

    private static final String X_PAY_NONCE = "X-Pay-Nonce";

    private static final String X_PAY_SIGN = "X-Pay-Sign";

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;

    private final PublicKey bizPublicKey;

    /**
     * 构造函数，注入业务系统公钥。
     *
     * @param bizPublicKey 业务系统公钥（验签入站请求）
     */
    public PayRsaSignService(final PublicKey bizPublicKey) {
        this.bizPublicKey = bizPublicKey;
    }

    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange, final String requestBody) {
        try {
            ServerHttpRequest request = exchange.getRequest();
            String timestamp = request.getHeaders().getFirst(X_PAY_TIMESTAMP);
            String nonce = request.getHeaders().getFirst(X_PAY_NONCE);
            String sign = request.getHeaders().getFirst(X_PAY_SIGN);

            if (timestamp == null || nonce == null || sign == null) {
                LOG.warn("[GW-Sign] 缺少签名头 ts={} nonce={} sign={}",
                        timestamp != null, nonce != null, sign != null);
                return VerifyResult.fail("missing X-Pay-* header");
            }

            // 时间戳防重放（±300s）
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return VerifyResult.fail("invalid timestamp format");
            }
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TIMESTAMP_TOLERANCE_SECONDS) {
                LOG.warn("[GW-Sign] 时间戳过期 ts={} now={} diff={}s", ts, now, Math.abs(now - ts));
                return VerifyResult.fail("timestamp expired");
            }

            // 解法A核心：用网关收到的原始路径（带 contextPath）
            // SignPlugin(order=50) 在 ContextPathPlugin(order=150) 之前执行，
            // 此时路径还是 /pay-demo/v3/pay/transactions/jsapi（带 contextPath）
            String method = request.getMethodValue().toUpperCase();
            String path = request.getURI().getPath();
            String query = request.getURI().getQuery();
            String url = (query == null) ? path : path + "?" + query;
            String body = requestBody == null ? "" : requestBody;

            // 5 行签名串：method\nurl\nts\nnonce\nbody\n
            String signString = method + "\n" + url + "\n" + timestamp + "\n"
                    + nonce + "\n" + body + "\n";
            LOG.info("[GW-Sign] 验签输入 | method={} url={} ts={} nonce={} bodyLen={} bodyPreview={}",
                    method, url, timestamp, nonce, body.length(),
                    body.length() > 100 ? body.substring(0, 100) + "..." : body);
            LOG.info("[GW-Sign] 待验签名串(5行)={}", signString.replace("\n", "↩"));
            LOG.info("[GW-Sign] 待验签sign={}", sign);

            // SHA256withRSA 验签
            byte[] sig = Base64.getDecoder().decode(sign);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(bizPublicKey);
            verifier.update(signString.getBytes(StandardCharsets.UTF_8));
            boolean pass = verifier.verify(sig);

            if (pass) {
                LOG.info("[GW-Sign] ✅ 验签通过（业务公钥 SHA256withRSA 校验成功）");
                return VerifyResult.success();
            } else {
                LOG.warn("[GW-Sign] ❌ 验签失败（签名串与sign不匹配）");
                return VerifyResult.fail("sign verify failed");
            }
        } catch (Exception e) {
            LOG.error("[GW-Sign] 验签异常", e);
            return VerifyResult.fail("verify error: " + e.getMessage());
        }
    }

    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange) {
        // GET 请求无 body，传空串
        return signatureVerify(exchange, "");
    }
}
