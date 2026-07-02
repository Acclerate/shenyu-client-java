/*
 * PAY Controller：验签入站请求 + 加签响应 + 触发回调。
 * 加 @ShenyuSpringMvcClient 注册到网关。
 */
package org.apache.shenyu.demo.sign.pay.controller;

import org.apache.shenyu.client.springmvc.annotation.ShenyuSpringMvcClient;
import org.apache.shenyu.demo.sign.pay.config.PaySignProperties;
import org.apache.shenyu.demo.sign.pay.service.NotifySender;
import org.apache.shenyu.demo.sign.pay.service.PayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PayController.
 *
 * <p>PAY 角色（支付服务）：验签业务系统入站请求 + 用私钥加签响应。
 * 注册到 ShenYu 网关，contextPath=/pay-demo，网关路由 /pay-demo/v3/pay/**。
 *
 * <p>注意：@ShenyuSpringMvcClient 的 path 是 PAY 自身的 Spring MVC 路径（不含 contextPath），
 * 网关侧会自动拼上 contextPath 前缀。
 */
@RestController
@RequestMapping("/v3/pay")
@ShenyuSpringMvcClient(path = "/v3/pay/**")
public class PayController {

    private static final Logger LOG = LoggerFactory.getLogger(PayController.class);

    private final PayService payService;

    private final NotifySender notifySender;

    private final PaySignProperties properties;

    public PayController(final PayService payService,
                         final NotifySender notifySender,
                         final PaySignProperties properties) {
        this.payService = payService;
        this.notifySender = notifySender;
        this.properties = properties;
    }

    /**
     * JSAPI 下单：验签请求 -> 业务处理 -> 加签响应。
     *
     * <p>阶段1直连：BIZ 直接调 http://localhost:8392/v3/pay/transactions/jsapi
     * <p>阶段2/3经网关：BIZ 调 http://localhost:9196/pay-demo/v3/pay/transactions/jsapi
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

        // 验签失败 -> 401
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

        // 用支付服务私钥对响应体加签
        String bodyJson = toJson(biz);
        Map<String, String> signHeaders = payService.signResponse(bodyJson);
        signHeaders.forEach(response::addHeader);

        LOG.info("[PAY] 下单成功 tradeNo={}，响应已加签", tradeNo);
        return ResponseEntity.ok(biz);
    }

    /**
     * 触发回调通知（演示用）：PAY 主动发回调给 BIZ（直连，不经网关）。
     *
     * @return 触发结果
     * @throws Exception 发送异常
     */
    @PostMapping("/notify-trigger")
    public Map<String, Object> triggerNotify() throws Exception {
        PayService.NotifyPayload payload = payService.buildNotify("DEMO" + System.currentTimeMillis());
        int respCode = notifySender.send(properties.getBizCallbackUrl(),
                payload.getBody(), payload.getHeaders());

        Map<String, Object> result = new HashMap<>();
        result.put("场景", "回调通知加签（PAY 直连 BIZ，不经网关）");
        result.put("PAY加签通知体", payload.getBody());
        result.put("PAY回调发送结果_httpCode", respCode);
        result.put("回调目标URL", properties.getBizCallbackUrl());
        return result;
    }

    private String toJson(final Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"code\":\"SUCCESS\"}";
        }
    }
}
