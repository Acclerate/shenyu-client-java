/*
 * BIZ Controller：发起支付 + 接收回调验签。
 */
package org.apache.shenyu.demo.sign.biz.controller;

import org.apache.shenyu.demo.sign.biz.security.PaySignInterceptor;
import org.apache.shenyu.demo.sign.biz.security.PaySignVerifier;
import org.apache.shenyu.demo.sign.biz.security.SignConstants;
import org.apache.shenyu.demo.sign.biz.client.PaySignClient;
import org.apache.shenyu.demo.sign.biz.config.BizSignProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * BizController.
 *
 * <p>BIZ 角色（业务系统）：
 * <ul>
 *   <li>{@code GET /biz/pay} —— 发起支付（自动加签请求 → 验签响应）</li>
 *   <li>{@code POST /biz/callback} —— 接收支付回调并验签（PAY 直连 BIZ，不经网关）</li>
 * </ul>
 */
@RestController
public class BizController {

    private static final Logger LOG = LoggerFactory.getLogger(BizController.class);

    private final PaySignClient paySignClient;

    private final BizSignProperties properties;

    private final PublicKey payPublicKey;

    public BizController(final PaySignClient paySignClient,
                         final BizSignProperties properties,
                         @Qualifier("payPublicKey") final PublicKey payPublicKey) {
        this.paySignClient = paySignClient;
        this.properties = properties;
        this.payPublicKey = payPublicKey;
    }

    /**
     * 发起支付：BIZ 加签请求 →（网关）→ PAY 验签并加签响应 → BIZ 验签响应。
     *
     * <p>URL 由 payBaseUrl 配置决定：
     * <ul>
     *   <li>阶段1直连：payBaseUrl=http://localhost:8392</li>
     *   <li>阶段2/3经网关：payBaseUrl=http://localhost:9196/pay-demo</li>
     * </ul>
     *
     * @return 全链路结果
     * @throws Exception 调用异常
     */
    @GetMapping("/biz/pay")
    public Map<String, Object> pay() throws Exception {
        String url = properties.getPayBaseUrl() + "/v3/pay/transactions/jsapi";
        String body = "{\"appid\":\"wxd678efh567hg6787\",\"mchid\":\"1900007291\","
                + "\"description\":\"Image形象店-深圳腾大-QQ公仔\","
                + "\"out_trade_no\":\"1217752501201407033233368018\","
                + "\"amount\":{\"total\":100,\"currency\":\"CNY\"}}";
        PaySignClient.PayResponse resp = paySignClient.pay(url, body);

        Map<String, Object> result = new HashMap<>();
        // 步骤1：BIZ 加签全过程（私钥加密）
        PaySignInterceptor.SignContext sc = resp.getSignContext();
        Map<String, Object> signDetail = new HashMap<>();
        if (sc != null) {
            signDetail.put("算法", "SHA256withRSA（业务私钥加密）");
            signDetail.put("method", sc.getMethod());
            signDetail.put("url", sc.getUrl());
            signDetail.put("timestamp", sc.getTimestamp());
            signDetail.put("nonce", sc.getNonce());
            signDetail.put("待签名串_5行_换行替换", sc.getSignString().replace("\n", "↩"));
            signDetail.put("签名值_sign", sc.getSign());
            signDetail.put("注入请求头", "X-Pay-Timestamp / X-Pay-Nonce / X-Pay-Sign");
        }
        result.put("步骤1_BIZ加签全过程_私钥加密", signDetail);
        result.put("步骤1_请求体", body);
        // 步骤2：PAY 处理
        result.put("步骤2_PAY处理并加签响应_httpCode", resp.getHttpCode());
        result.put("步骤2_PAY响应体", resp.getBody());
        result.put("步骤2_响应签名头", resp.getTimestamp() == null ? "无(验签失败或非加签响应)"
                : "ts=" + resp.getTimestamp() + " nonce=" + resp.getNonce());
        // 步骤3：BIZ 验签响应（公钥验签）
        result.put("步骤3_BIZ响应验签结果", resp.isVerifyPass() ? "通过（PAY公钥验签成功）" : "失败");
        result.put("目标URL", url);
        return result;
    }

    /**
     * 接收支付回调通知并验签（PAY 直连 BIZ，不经网关）。
     *
     * @param timestamp 回调头 X-Pay-Timestamp
     * @param nonce     回调头 X-Pay-Nonce
     * @param sign      回调头 X-Pay-Sign
     * @param body      回调报文主体原文
     * @return 验签结果
     */
    @PostMapping(value = "/biz/callback")
    public Map<String, Object> callback(
            @RequestHeader(value = SignConstants.X_PAY_TIMESTAMP, required = false) final String timestamp,
            @RequestHeader(value = SignConstants.X_PAY_NONCE, required = false) final String nonce,
            @RequestHeader(value = SignConstants.X_PAY_SIGN, required = false) final String sign,
            @RequestBody(required = false) final String body) {
        String rawBody = body == null ? "" : body;
        boolean tsOk = PaySignVerifier.checkTimestamp(timestamp, properties.getTimestampToleranceSeconds());
        boolean signOk = PaySignVerifier.verifyCallback(timestamp, nonce, rawBody, sign, payPublicKey);
        LOG.info("[BIZ] 收到回调 | tsOk={} signOk={} body={}", tsOk, signOk, rawBody);

        Map<String, Object> result = new HashMap<>();
        result.put("场景", "回调通知验签（PAY 直连 BIZ，不经网关）");
        result.put("时间戳时效校验", tsOk ? "通过" : "失败/超时");
        result.put("回调验签结果", signOk ? "通过" : "失败");
        result.put("回调报文", rawBody);
        result.put("httpStatus", signOk && tsOk ? 200 : 401);
        return result;
    }
}
