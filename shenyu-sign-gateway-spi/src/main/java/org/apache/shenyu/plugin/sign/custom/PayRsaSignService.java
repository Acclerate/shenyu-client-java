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
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
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

            private static final String X_PAY_APP_KEY = "X-Pay-App-Key";

    private static final long   TIMESTAMP_TOLERANCE_SECONDS = 300L;

    private final BizPublicKeyProvider bizPublicKeyProvider;

    /**
     * 构造函数，注入业务系统公钥。
     *
     * @param bizPublicKeyProvider 业务系统公钥提供器（支持 Redis 动态轮换 + 本地缓存）
     */
    public PayRsaSignService(final BizPublicKeyProvider bizPublicKeyProvider) {
        this.bizPublicKeyProvider = bizPublicKeyProvider;
    }

    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange, final String requestBody) {
        try {
            ServerHttpRequest request = exchange.getRequest();
            String timestamp = request.getHeaders().getFirst(X_PAY_TIMESTAMP);
            String nonce = request.getHeaders().getFirst(X_PAY_NONCE);
            String sign = request.getHeaders().getFirst(X_PAY_SIGN);
            String appKey = request.getHeaders().getFirst(X_PAY_APP_KEY);

            if (timestamp == null || nonce == null || sign == null) {
                LOG.warn("[GW-Sign] 缺少签名头 ts={} nonce={} sign={}",
                        timestamp != null, nonce != null, sign != null);
                return fail401(exchange, "missing X-Pay-* header");
            }
            if (appKey == null || appKey.trim().isEmpty()) {
                LOG.warn("[GW-Sign] 缺少 X-Pay-App-Key 头");
                return fail401(exchange, "missing X-Pay-App-Key header");
            }

            // 时间戳防重放（±300s）
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                return fail401(exchange, "invalid timestamp format");
            }
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TIMESTAMP_TOLERANCE_SECONDS) {
                LOG.warn("[GW-Sign] 时间戳过期 ts={} now={} diff={}s", ts, now, Math.abs(now - ts));
                return fail401(exchange, "timestamp expired");
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
            LOG.info("[GW-Sign] 验签输入 | appKey={} method={} url={} ts={} nonce={} bodyLen={} bodyPreview={}",
                    appKey, method, url, timestamp, nonce, body.length(),
                    body.length() > 100 ? body.substring(0, 100) + "..." : body);
            LOG.info("[GW-Sign] 待验签名串(5行)={}", signString.replace("\n", "↩"));
            LOG.info("[GW-Sign] 待验签sign={}", sign);

            // SHA256withRSA 验签（根据 appKey 从 Redis 获取对应公钥）
            byte[] sig = Base64.getDecoder().decode(sign);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(bizPublicKeyProvider.currentKey(appKey));
            verifier.update(signString.getBytes(StandardCharsets.UTF_8));
            boolean pass = verifier.verify(sig);

            if (pass) {
                LOG.info("[GW-Sign] ✅ 验签通过 appKey={}（业务公钥 SHA256withRSA 校验成功）", appKey);
                return VerifyResult.success();
            } else {
                LOG.warn("[GW-Sign] ❌ 验签失败 appKey={}（签名串与sign不匹配）", appKey);
                return fail401(exchange, "sign verify failed");
            }
        } catch (Exception e) {
            LOG.error("[GW-Sign] 验签异常", e);
            return fail401(exchange, "verify error: " + e.getMessage());
        }
    }

    /**
     * 统一失败出口：设置真实 HTTP 401 状态码 + 返回 VerifyResult.fail.
     *
     * <p>ShenYu 原生 {@code WebFluxResultUtils.result()} 在写错误 body 时不会设置 HTTP 状态码
     * （默认 200，错误码只进 body JSON）。本方法在 SignPlugin 调用 failedResult 写 body 之前，
     * 先给 exchange 的 response 设置 {@link HttpStatus#UNAUTHORIZED}，
     * 让验签失败时传输层也返回真实 401，而非 200。
     *
     * <p>此时 response 尚未 committed（SignPlugin 还在 doExecute 中），
     * 后续 writeWith 会沿用此状态码。
     *
     * @param exchange 网关交换器
     * @param reason   失败原因（进 body JSON 的 message 字段）
     * @return VerifyResult.fail(reason)
     */
    private VerifyResult fail401(final ServerWebExchange exchange, final String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return VerifyResult.fail(reason);
    }

    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange) {
        // GET 请求无 body，传空串
        return signatureVerify(exchange, "");
    }
}
