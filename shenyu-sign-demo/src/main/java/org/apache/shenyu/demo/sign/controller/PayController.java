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

import org.apache.shenyu.client.core.sign.SignConstants;
import org.apache.shenyu.demo.sign.service.PayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PayController.
 *
 * <p>PAY 角色（支付服务）：验签业务系统入站请求 + 用私钥加签响应。
 * 对应需求文档「验签」第一个时序图。
 */
@RestController
@RequestMapping("/v3/pay")
public class PayController {

    private static final Logger LOG = LoggerFactory.getLogger(PayController.class);

    private final PayService payService;

    public PayController(final PayService payService) {
        this.payService = payService;
    }

    /**
     * JSAPI 下单：验签请求 → 业务处理 → 加签响应。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应（用于写入加签头）
     * @return 响应体
     * @throws IOException 读取请求体失败
     */
    @PostMapping("/transactions/jsapi")
    public ResponseEntity<Object> jsapi(final HttpServletRequest request,
                                        final HttpServletResponse response) throws IOException {
        PayService.VerifyOutcome outcome = payService.verifyBizRequest(request);

        // 验签失败 → 401
        if (!outcome.isSignPass() || !outcome.isTimestampPass()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", "SIGN_ERROR");
            err.put("message", "签名验证失败 signPass=" + outcome.isSignPass()
                    + " tsPass=" + outcome.isTimestampPass());
            LOG.warn("[PAY] 验签失败，拒绝请求");
            return ResponseEntity.status(401).body(err);
        }

        // 业务处理：构造下单响应
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> biz = new HashMap<>();
        biz.put("code", "SUCCESS");
        biz.put("message", "ok");
        Map<String, Object> data = new HashMap<>();
        data.put("trade_no", tradeNo);
        data.put("prepay_id", "wx" + tradeNo.substring(0, 16));
        biz.put("data", data);

        // 用支付服务私钥对响应体加签（com.google.gson.Gson 在 shenyu-client-core 传递依赖中；
        // 这里直接用 Spring 自带 jackson 序列化）
        String bodyJson = toJson(biz);
        Map<String, String> signHeaders = payService.signResponse(bodyJson);
        signHeaders.forEach(response::addHeader);

        LOG.info("[PAY] 下单成功 tradeNo={}，响应已加签", tradeNo);
        return ResponseEntity.ok(biz);
    }

    private String toJson(final Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            // 兜底
            return "{\"code\":\"SUCCESS\"}";
        }
    }
}
