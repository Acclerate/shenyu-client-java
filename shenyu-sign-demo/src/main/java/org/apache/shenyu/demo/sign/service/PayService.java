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

package org.apache.shenyu.demo.sign.service;

import org.apache.shenyu.client.core.sign.PaySignVerifier;
import org.apache.shenyu.client.core.sign.RsaSigner;
import org.apache.shenyu.client.core.sign.SignConstants;
import org.apache.shenyu.client.core.sign.SignStringBuilder;
import org.apache.shenyu.demo.sign.config.PaySignProperties;
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
     * @param request HTTP 请求
     * @return 验签结果
     * @throws IOException 读取请求体失败
     */
    public VerifyOutcome verifyBizRequest(final HttpServletRequest request) throws IOException {
        String timestamp = request.getHeader(SignConstants.X_PAY_TIMESTAMP);
        String nonce = request.getHeader(SignConstants.X_PAY_NONCE);
        String sign = request.getHeader(SignConstants.X_PAY_SIGN);
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String url = (query == null) ? uri : uri + "?" + query;
        String body = readBody(request);

        boolean tsOk = PaySignVerifier.checkTimestamp(timestamp, properties.getTimestampToleranceSeconds());
        boolean signOk;
        if (timestamp == null || nonce == null || sign == null) {
            signOk = false;
        } else {
            String signString = SignStringBuilder.buildRequestSignString(method, url, timestamp, nonce, body);
            signOk = RsaSigner.verify(signString, sign, bizPublicKey);
        }
        LOG.info("[PAY] 验签业务请求 | method={} url={} ts={} nonce={} tsOk={} signOk={}",
                method, url, timestamp, nonce, tsOk, signOk);
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
        LOG.info("[PAY] 响应加签 | ts={} nonce={} signLen={}", timestamp, nonce, sign.length());
        return headers;
    }

    /**
     * 构造回调通知（用支付服务私钥加签），返回通知体 + 签名头，供 HTTP 发送给业务系统。
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
