/*
 * PayRsaSignService —— 网关侧 RSA 验签核心实现。
 *
 * 关键点：网关侧验签取 exchange.getRequest().getURI().getPath() 得到网关收到的原始路径
 * （带 contextPath，如 /pay-demo/v3/pay/transactions/jsapi）。
 * 此时 SignPlugin(order=50) 在 ContextPathPlugin(order=150) 之前执行，路径还没被剥离，
 * 与业务方加签时用的路径完全一致，天然匹配。
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
 *
 * <p>时间戳契约：X-Pay-Timestamp 由加签客户端以 <b>epoch 毫秒</b> 发送（pay-sign-standalone 用
 * System.currentTimeMillis()）。本端比较时统一换算为秒，与 Instant.now().getEpochSecond() 比较，容差 ±300s。
 */
public class PayRsaSignService implements SignService {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignService.class);

    private static final String X_PAY_TIMESTAMP = "X-Pay-Timestamp";

    private static final String X_PAY_NONCE = "X-Pay-Nonce";

    private static final String X_PAY_SIGN = "X-Pay-Sign";

    private static final String X_PAY_APP_KEY = "X-Pay-App-Key";

    /** 时间戳防重放窗口（秒） */
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;

    /** 验签输入日志中 body 预览的最大长度 */
    private static final int BODY_PREVIEW_LIMIT = 100;

    /** 防重放键前缀（严格契约：replay:{appKey}:{timestamp}:{nonce}） */
    private static final String REPLAY_KEY_PREFIX = "replay:";

    private final BizPublicKeyProvider bizPublicKeyProvider;

    /** 防重放守卫（可为 null = 功能关闭，行为与旧版一致） */
    private final PayReplayGuard replayGuard;

    /**
     * 构造函数（含防重放检查，replayGuard 为 null/未启用时跳过，兼容旧用法/单测）。
     *
     * @param bizPublicKeyProvider 业务系统公钥提供器（admin plugin.config 源 + 本地缓存）
     */
    public PayRsaSignService(final BizPublicKeyProvider bizPublicKeyProvider, PayReplayGuard replayGuard) {
        this.bizPublicKeyProvider = bizPublicKeyProvider;
        this.replayGuard = replayGuard;
    }


    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange, final String requestBody) {
        try {
            final ServerHttpRequest request = exchange.getRequest();
            final SignHeaders headers = extractHeaders(request);
            validateHeaders(headers);

            parseAndValidateTimestamp(headers.getTimestamp());

            // 防重放：nonce 唯一性校验（spec 步骤2，先于签名验证）
            final VerifyResult replay = replayCheck(exchange, headers.getAppKey(),
                    headers.getTimestamp(), headers.getNonce());
            if (!replay.isSuccess()) {
                return replay;
            }

            final String signString = buildSignString(headers, requestBody);
            logVerificationInput(headers, requestBody, signString);

            if (verifySignature(headers.getAppKey(), signString, headers.getSign())) {
                LOG.info("[GW-Sign]  验签通过 appKey={}", headers.getAppKey());
                return VerifyResult.success();
            }
            LOG.warn("[GW-Sign]  验签失败 appKey={}（签名串与 sign 不匹配）", headers.getAppKey());
            return fail401(exchange, "sign verify failed");
        } catch (final SignVerificationException ex) {
            // 可预期的校验失败（缺头 / 时间戳非法或过期等），reason 已描述原因
            LOG.warn("[GW-Sign] {}", ex.getMessage());
            return fail401(exchange, ex.getReason());
        } catch (final IllegalStateException ex) {
            // admin 未下发该 appKey 的公钥（currentKey 抛出）
            LOG.warn("[GW-Sign] 验签失败：{}", ex.getMessage());
            return fail401(exchange, "invalid appKey or public key not configured");
        } catch (final Exception ex) {
            // 底层异常（Base64 解码失败、算法不可用等）
            LOG.error("[GW-Sign] 验签异常", ex);
            return fail401(exchange, "verify error: " + ex.getMessage());
        }
    }

    // ================= 防重放（replay）=================

    /**
     * 防重放检查：拦截同一 (appKey, timestamp, nonce) 的重放请求。
     *
     * <p>Key 契约（严格）：{@code replay:{appKey}:{timestamp}:{nonce}}。
     * <ul>
     *   <li>guard 为 null / 未启用 → 直接放行（与旧版行为一致）</li>
     *   <li>首次出现（SET NX 成功）→ 标记并放行</li>
     *   <li>key 已存在（重放）→ 401 "replay request detected"</li>
     * </ul>
     *
     * <p>校验顺序（spec）：时间戳有效 → nonce 唯一(replay) → 签名正确。
     * 故本步在 {@link #verifySignature} 之前执行；fail-open 下 Redis 异常亦放行，不阻断支付。
     *
     * @param exchange exchange
     * @param appKey 验签通过的 appKey
     * @param timestamp X-Pay-Timestamp 原始头值（epoch 毫秒）
     * @param nonce X-Pay-Nonce 原始头值
     * @return 放行 success / 401 fail
     */
    private VerifyResult replayCheck(final ServerWebExchange exchange, final String appKey,
                                     final String timestamp, final String nonce) {
        if (replayGuard == null || !replayGuard.getProps().isEnabled()) {
            return VerifyResult.success();
        }
        final String key = REPLAY_KEY_PREFIX + appKey + ":" + timestamp.trim() + ":" + nonce.trim();
        if (replayGuard.tryMark(key)) {
            return VerifyResult.success();
        }
        LOG.warn("[GW-Replay]  重放请求被拦截 key={}", key);
        return fail401(exchange, "replay request detected");
    }

    /**
     * 统一失败出口：设置真实 HTTP 401 状态码 + 返回 VerifyResult.fail.
     *
     * <p>ShenYu 原生 {@code WebFluxResultUtils.result()} 在写错误 body 时不会设置 HTTP 状态码
     * （默认 200，错误码只进 body JSON）。本方法在 SignPlugin 调用 failedResult 写 body 之前，
     * 先给 exchange 的 response 设置 {@link HttpStatus#UNAUTHORIZED}，
     * 让验签失败时传输层也返回真实 401，而非 200。
     */
    private VerifyResult fail401(final ServerWebExchange exchange, final String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return VerifyResult.fail(reason);
    }

    /** 从请求头抽取验签所需的字段（含 method / url 等派生值） */
    private SignHeaders extractHeaders(final ServerHttpRequest request) {
        final String timestamp = request.getHeaders().getFirst(X_PAY_TIMESTAMP);
        final String nonce = request.getHeaders().getFirst(X_PAY_NONCE);
        final String sign = request.getHeaders().getFirst(X_PAY_SIGN);
        final String appKey = request.getHeaders().getFirst(X_PAY_APP_KEY);
        final String method = request.getMethodValue().toUpperCase();
        final String url = buildRequestUrl(request);
        return new SignHeaders(method, timestamp, nonce, sign, appKey, url);
    }

    /** 校验必备签名头与 appKey 是否齐备；缺失则抛出 {@link SignVerificationException} */
    private void validateHeaders(final SignHeaders headers) {
        if (isBlank(headers.getTimestamp()) || isBlank(headers.getNonce()) || isBlank(headers.getSign())) {
            LOG.warn("[GW-Sign] 缺少签名头：X-Pay-Timestamp={} X-Pay-Nonce={} X-Pay-Sign={}",
                    headers.getTimestamp(), headers.getNonce(), headers.getSign());
            throw new SignVerificationException("missing X-Pay-* header");
        }
        if (isBlank(headers.getAppKey())) {
            LOG.warn("[GW-Sign] 缺少 X-Pay-App-Key 头");
            throw new SignVerificationException("missing X-Pay-App-Key header");
        }
    }

    /**
     * 解析并校验时间戳：非法格式或超出 ±容差窗口都抛 {@link SignVerificationException}。
     *
     * @param timestamp X-Pay-Timestamp 原始头值（epoch 毫秒）
     */
    private void parseAndValidateTimestamp(final String timestamp) {
        final long tsMillis;
        try {
            tsMillis = Long.parseLong(timestamp.trim());
        } catch (final NumberFormatException e) {
            throw new SignVerificationException("invalid timestamp format");
        }
        final long tsSec = tsMillis / 1000L;
        final long nowSec = Instant.now().getEpochSecond();
        final long diff = Math.abs(nowSec - tsSec);
        if (diff > TIMESTAMP_TOLERANCE_SECONDS) {
            LOG.warn("[GW-Sign] 时间戳过期 ts={} ({}s) now={}s diff={}s",
                    tsMillis, tsSec, nowSec, diff);
            throw new SignVerificationException("timestamp expired");
        }
    }

    /** 构造带 contextPath 的原始请求 URL（GET 含 query string） */
    private String buildRequestUrl(final ServerHttpRequest request) {
        final String path = request.getURI().getPath();
        final String query = request.getURI().getQuery();
        return query == null ? path : path + "?" + query;
    }

    /** 构造 5 行签名串：method\nurl\nts\nnonce\nbody\n */
    private String buildSignString(final SignHeaders headers, final String requestBody) {
        final String body = requestBody == null ? "" : requestBody;
        return headers.getMethod() + "\n"
                + headers.getUrl() + "\n"
                + headers.getTimestamp() + "\n"
                + headers.getNonce() + "\n"
                + body + "\n";
    }

    /** SHA256withRSA 验签（按 appKey 取公钥） */
    private boolean verifySignature(final String appKey, final String signString, final String signatureBase64) throws Exception {
        final byte[] signature = Base64.getDecoder().decode(signatureBase64);
        final Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(bizPublicKeyProvider.currentKey(appKey));
        verifier.update(signString.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    /** 输出验签输入的诊断日志（含 5 行签名串预览） */
    private void logVerificationInput(final SignHeaders headers, final String requestBody, final String signString) {
        final String body = requestBody == null ? "" : requestBody;
        final String bodyPreview = body.length() > BODY_PREVIEW_LIMIT
                ? body.substring(0, BODY_PREVIEW_LIMIT) + "..." : body;
        LOG.info("[GW-Sign] 验签输入 | appKey={} method={} url={} ts={} nonce={} bodyLen={} bodyPreview={}",
                headers.getAppKey(), headers.getMethod(), headers.getUrl(), headers.getTimestamp(),
                headers.getNonce(), body.length(), bodyPreview);
        LOG.info("[GW-Sign] 待验签名串(5行)={}", signString.replace("\n", "↩"));
        LOG.info("[GW-Sign] 待验签sign={}", headers.getSign());
    }

    private static boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public VerifyResult signatureVerify(final ServerWebExchange exchange) {
        // GET 请求无 body，传空串
        return signatureVerify(exchange, "");
    }

    /**
     * 可预期的验签失败：携带给调用方的失败原因（进 401 body 的 message）。
     * 与底层不可预期的 {@link Exception} 区分，便于统一出口处理。
     */
    private static final class SignVerificationException extends RuntimeException {

        private final String reason;

        private SignVerificationException(final String reason) {
            super(reason);
            this.reason = reason;
        }

        private String getReason() {
            return reason;
        }
    }

    /** 单次验签所需的请求头与派生字段（不可变） */
    private static final class SignHeaders {

        private final String method;

        private final String timestamp;

        private final String nonce;

        private final String sign;

        private final String appKey;

        private final String url;

        private SignHeaders(final String method, final String timestamp, final String nonce,
                             final String sign, final String appKey, final String url) {
            this.method = method;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.sign = sign;
            this.appKey = appKey;
            this.url = url;
        }

        private String getMethod() {
            return method;
        }

        private String getTimestamp() {
            return timestamp;
        }

        private String getNonce() {
            return nonce;
        }

        private String getSign() {
            return sign;
        }

        private String getAppKey() {
            return appKey;
        }

        private String getUrl() {
            return url;
        }
    }
}
