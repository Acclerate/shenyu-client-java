/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.demo.sign.controller;

import org.apache.shenyu.client.core.sign.PaySignVerifier;
import org.apache.shenyu.client.core.sign.SignConstants;
import org.apache.shenyu.demo.sign.config.PaySignProperties;
import org.apache.shenyu.demo.sign.service.NotifySender;
import org.apache.shenyu.demo.sign.service.PayService;
import org.apache.shenyu.demo.sign.service.PaySignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * BizController.
 *
 * <p>BIZ 角色（业务系统）：
 * <ul>
 *   <li>{@code GET /biz/pay} —— 发起支付（自动加签请求 → 验签响应），演示加签+响应验签闭环</li>
 *   <li>{@code POST /biz/callback} —— 接收支付回调并验签，演示回调加签+验签闭环</li>
 *   <li>{@code POST /v3/pay/notify-trigger} —— 触发支付服务主动发回调（演示用）</li>
 * </ul>
 */
@RestController
public class BizController {

    private static final Logger LOG = LoggerFactory.getLogger(BizController.class);

    private final PaySignClient paySignClient;

    private final PaySignProperties properties;

    private final PublicKey payPublicKey;

    private final PayService payService;

    private final NotifySender notifySender;

    public BizController(final PaySignClient paySignClient,
                         final PaySignProperties properties,
                         @Qualifier("payPublicKey") final PublicKey payPublicKey,
                         final PayService payService,
                         final NotifySender notifySender) {
        this.paySignClient = paySignClient;
        this.properties = properties;
        this.payPublicKey = payPublicKey;
        this.payService = payService;
        this.notifySender = notifySender;
    }

    /**
     * 发起支付：BIZ 加签请求 → PAY 验签并加签响应 → BIZ 验签响应。
     *
     * @return 全链路结果（请求加签、PAY 响应、响应验签）
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
        result.put("步骤1_BIZ加签请求", "已自动加签（见请求 X-Pay-* 头）");
        result.put("步骤2_PAY处理并加签响应_httpCode", resp.getHttpCode());
        result.put("步骤2_PAY响应体", resp.getBody());
        result.put("步骤3_BIZ响应验签结果", resp.isVerifyPass() ? "通过 ✓" : "失败 ✗");
        result.put("响应签名头_ts", resp.getTimestamp());
        result.put("响应签名头_nonce", resp.getNonce());
        return result;
    }

    /**
     * 接收支付回调通知并验签。
     *
     * @param timestamp 回调头 X-Pay-Timestamp
     * @param nonce     回调头 X-Pay-Nonce
     * @param sign      回调头 X-Pay-Sign
     * @param body      回调报文主体原文（务必使用原始报文）
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
        result.put("场景", "回调通知验签（附件2第二个时序图）");
        result.put("时间戳时效校验", tsOk ? "通过" : "失败/超时");
        result.put("回调验签结果", signOk ? "通过 ✓" : "失败 ✗");
        result.put("回调报文", rawBody);
        result.put("httpStatus", signOk && tsOk ? 200 : 401);
        return result;
    }

    /**
     * 触发支付服务主动发起回调（演示用）。
     *
     * @return 触发结果
     * @throws Exception 发送异常
     */
    @PostMapping("/v3/pay/notify-trigger")
    public Map<String, Object> triggerNotify() throws Exception {
        PayService.NotifyPayload payload = payService.buildNotify("DEMO" + System.currentTimeMillis());
        int respCode = notifySender.send(properties.getBizCallbackUrl(),
                payload.getBody(), payload.getHeaders());

        Map<String, Object> result = new HashMap<>();
        result.put("场景", "回调通知加签（附件2第二个时序图）");
        result.put("PAY加签通知体", payload.getBody());
        result.put("PAY回调发送结果_httpCode", respCode);
        return result;
    }
}
