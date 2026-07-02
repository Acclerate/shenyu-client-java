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

package org.apache.shenyu.client.core.sign;

import okhttp3.Headers;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/**
 * PaySignVerifier.
 *
 * <p>支付服务响应验签与回调通知验签（业务系统侧）。提供三组能力：
 * <ol>
 *   <li>{@link #verifyResponse} —— 验证支付服务 HTTP 响应（从 OkHttp {@link Response} 提取头与体）</li>
 *   <li>{@link #verifyCallback} —— 验证支付服务异步回调通知（接收显式参数，适配 Servlet）</li>
 *   <li>{@link #verifyRequest} —— 验证业务系统请求（支付服务侧验签入站，适配 Servlet {@code HttpServletRequest}）</li>
 *   <li>{@link #checkTimestamp} —— 时间戳时效校验，防重放</li>
 * </ol>
 *
 * <p>验签签名串统一为 3 行格式（{@link SignStringBuilder#buildResponseSignString}）：
 * {@code 应答时间戳\n应答随机串\n应答报文主体\n}。
 *
 * <p>使用支付服务的公钥做 SHA256withRSA 验签。
 */
public final class PaySignVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(PaySignVerifier.class);

    private PaySignVerifier() {
    }

    /**
     * 验证 OkHttp {@link Response}（支付服务响应）.
     *
     * <p>注意：调用本方法会消费响应体，应在读取响应内容后调用，或自行提前缓存。
     *
     * @param response      OkHttp 响应对象
     * @param payPublicKey  支付服务公钥
     * @return 验签结果（含时间戳、nonce、是否通过）
     * @throws IOException 读取响应体失败
     */
    public static VerifyResult verifyResponse(final Response response, final PublicKey payPublicKey) throws IOException {
        Headers headers = response.headers();
        String timestamp = headers.get(SignConstants.X_PAY_TIMESTAMP);
        String nonce = headers.get(SignConstants.X_PAY_NONCE);
        String sign = headers.get(SignConstants.X_PAY_SIGN);
        // 注意：response.body().string() 只能调用一次，会消费并关闭 body
        String body = Objects.nonNull(response.body()) ? response.body().string() : "";
        boolean pass = doVerify(timestamp, nonce, body, sign, payPublicKey);
        return new VerifyResult(timestamp, nonce, sign, body, pass);
    }

    /**
     * 验证支付服务异步回调通知（业务系统接收回调时使用）.
     *
     * @param timestamp    回调头中的时间戳
     * @param nonce        回调头中的随机串
     * @param rawBody      回调报文主体原文（务必使用原始报文，框架不得篡改）
     * @param sign         回调头中的签名值
     * @param payPublicKey 支付服务公钥
     * @return {@code true} 验签通过
     */
    public static boolean verifyCallback(final String timestamp, final String nonce,
                                         final String rawBody, final String sign,
                                         final PublicKey payPublicKey) {
        return doVerify(timestamp, nonce, rawBody, sign, payPublicKey);
    }

    /**
     * 验证业务系统请求（支付服务侧验签入站请求，3 行格式不适用，这里复用 5 行格式的请求签名串）.
     *
     * <p>支付服务端从 {@link HttpServletRequestLite} 抽象读取 method/URI/body，
     * 使用业务系统公钥验签。
     *
     * @param request       请求抽象（封装 method / URI / body / 头）
     * @param bizPublicKey  业务系统公钥
     * @return {@code true} 验签通过
     */
    public static boolean verifyRequest(final HttpServletRequestLite request, final PublicKey bizPublicKey) {
        String timestamp = request.header(SignConstants.X_PAY_TIMESTAMP);
        String nonce = request.header(SignConstants.X_PAY_NONCE);
        String sign = request.header(SignConstants.X_PAY_SIGN);
        String method = Objects.isNull(request.method()) ? "GET" : request.method().toUpperCase();
        // URI 取 path + query，与加签侧 PaySignInterceptor 保持一致
        String url = request.requestUri();
        String body = request.body();
        String signString = SignStringBuilder.buildRequestSignString(method, url, timestamp, nonce, body);
        return RsaSigner.verify(signString, sign, bizPublicKey);
    }

    /**
     * 时间戳时效校验（防重放），请求/响应/回调均可使用.
     *
     * @param timestamp         秒级时间戳
     * @param toleranceSeconds  允许偏差秒数（建议 {@link SignConstants#DEFAULT_TIMESTAMP_TOLERANCE_SECONDS}）
     * @return {@code true} 时间戳在允许偏差内
     */
    public static boolean checkTimestamp(final String timestamp, final long toleranceSeconds) {
        try {
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            return Math.abs(now - ts) <= toleranceSeconds;
        } catch (NumberFormatException e) {
            LOG.warn("timestamp format invalid: {}", timestamp);
            return false;
        }
    }

    private static boolean doVerify(final String timestamp, final String nonce, final String body,
                                    final String sign, final PublicKey publicKey) {
        if (Objects.isNull(timestamp) || Objects.isNull(nonce) || Objects.isNull(sign)) {
            LOG.warn("验签失败：缺少必要头字段 ts={} nonce={} sign={}", timestamp, nonce, Objects.isNull(sign) ? "null" : "present");
            return false;
        }
        String signString = SignStringBuilder.buildResponseSignString(timestamp, nonce, body);
        return RsaSigner.verify(signString, sign, publicKey);
    }

    /**
     * HttpServletRequestLite.
     *
     * <p>支付服务侧验签入站请求所需的请求抽象，解耦具体 Servlet API，
     * 既可适配 {@code javax.servlet.http.HttpServletRequest}，也可适配 Spring/WebFlux。
     */
    public interface HttpServletRequestLite {

        /**
         * 读取请求头.
         *
         * @param name 头名称
         * @return 头值
         */
        String header(String name);

        /**
         * HTTP 方法（GET/POST 等）.
         *
         * @return 方法名
         */
        String method();

        /**
         * 请求 URI（path + query，不含 scheme/host）.
         *
         * @return 请求 URI
         */
        String requestUri();

        /**
         * 请求报文主体原文.
         *
         * @return 请求体
         */
        String body();
    }

    /**
     * VerifyResult.
     *
     * <p>响应验签结果载体，便于业务系统记录日志或返回调用方。
     */
    public static final class VerifyResult {

        private final String timestamp;

        private final String nonce;

        private final String sign;

        private final String body;

        private final boolean pass;

        VerifyResult(final String timestamp, final String nonce, final String sign,
                     final String body, final boolean pass) {
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.sign = sign;
            this.body = body;
            this.pass = pass;
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

        public String getBody() {
            return body;
        }

        public boolean isPass() {
            return pass;
        }
    }
}
