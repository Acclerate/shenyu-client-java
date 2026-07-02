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

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.shenyu.client.core.sign.PaySignInterceptor;
import org.apache.shenyu.client.core.sign.PaySignVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * PaySignClient.
 *
 * <p>BIZ 角色（业务系统）出站客户端：注入 {@link PaySignInterceptor} 自动对每个请求加签，
 * 收到响应后用支付服务公钥验签。对应需求文档「加签」+「验签」第一个时序图。
 */
@Component
public class PaySignClient {

    private static final Logger LOG = LoggerFactory.getLogger(PaySignClient.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final PublicKey payPublicKey;

    public PaySignClient(@Qualifier("bizPrivateKey") final PrivateKey bizPrivateKey,
                         @Qualifier("payPublicKey") final PublicKey payPublicKey) {
        // 关键：OkHttpClient 注入 PaySignInterceptor，对所有出站请求自动加签
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(new PaySignInterceptor(bizPrivateKey))
                .build();
        this.payPublicKey = payPublicKey;
    }

    /**
     * 发送加签请求并验签响应。
     *
     * @param url      支付服务接口 URL
     * @param jsonBody 请求体（JSON）
     * @return 含原始响应体与验签结果的载体
     * @throws Exception IO 或验签异常
     */
    public PayResponse pay(final String url, final String jsonBody) throws Exception {
        RequestBody body = RequestBody.create(JSON, jsonBody);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        LOG.info("[BIZ] 发起支付请求 url={} bodyLen={}", url, jsonBody.length());

        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            // 注意：verifyResponse 会消费并关闭 body
            PaySignVerifier.VerifyResult verifyResult = PaySignVerifier.verifyResponse(response, payPublicKey);
            LOG.info("[BIZ] 收到响应 httpCode={} 响应验签={}", code, verifyResult.isPass() ? "通过 ✓" : "失败 ✗");
            return new PayResponse(code, verifyResult.getBody(), verifyResult.isPass(),
                    verifyResult.getTimestamp(), verifyResult.getNonce(), verifyResult.getSign());
        }
    }

    /**
     * PayResponse.
     */
    public static final class PayResponse {

        private final int httpCode;

        private final String body;

        private final boolean verifyPass;

        private final String timestamp;

        private final String nonce;

        private final String sign;

        PayResponse(final int httpCode, final String body, final boolean verifyPass,
                    final String timestamp, final String nonce, final String sign) {
            this.httpCode = httpCode;
            this.body = body;
            this.verifyPass = verifyPass;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.sign = sign;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getBody() {
            return body;
        }

        public boolean isVerifyPass() {
            return verifyPass;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getNonce() {
            return nonce;
        }

        public String getSign() {
            return sign;
        }
    }
}
