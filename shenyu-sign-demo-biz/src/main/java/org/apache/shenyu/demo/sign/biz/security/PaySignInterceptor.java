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

package org.apache.shenyu.demo.sign.biz.security;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PaySignInterceptor.
 *
 * <p>OkHttp 出站请求加签拦截器（业务系统侧）。对标的实现思路为
 * wechatpay-apache-httpclient 的 {@code Credentials}。
 *
 * <p>拦截每个出站请求，自动完成：
 * <ol>
 *   <li>生成秒级时间戳与 32 位 hex 随机串（nonce）</li>
 *   <li>读取请求体原文（通过 {@link Buffer} 缓冲，避免消费流）</li>
 *   <li>构造 5 行待签名串（{@link SignStringBuilder#buildRequestSignString}）</li>
 *   <li>使用业务系统私钥做 SHA256withRSA 签名（{@link RsaSigner#sign}）</li>
 *   <li>注入 {@code X-Pay-Timestamp} / {@code X-Pay-Nonce} / {@code X-Pay-Sign} 头</li>
 * </ol>
 *
 * <p>使用方式：构建 OkHttpClient 时通过 {@code addNetworkInterceptor} 或 {@code addInterceptor} 注入。
 */
public final class PaySignInterceptor implements Interceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PaySignInterceptor.class);

    /**
     * ThreadLocal 持有最近一次加签详情，供调用方（如 BizController）在响应中展示加签过程.
     * 同步 OkHttp 调用栈内有效，请求结束应 {@link #clearSignContext()}。
     */
    private static final ThreadLocal<SignContext> LAST_SIGN = new ThreadLocal<>();

    private final PrivateKey bizPrivateKey;

    private final String appKey;

    /**
     * 构造加签拦截器.
     *
     * @param bizPrivateKey 业务系统私钥（用于对出站请求加签）
     * @param appKey        应用标识（注入 X-Pay-App-Key 头，网关据此从 Redis 获取对应公钥）
     */
    public PaySignInterceptor(final PrivateKey bizPrivateKey, final String appKey) {
        this.bizPrivateKey = bizPrivateKey;
        this.appKey = appKey;
    }

    @Override
    public Response intercept(final Chain chain) throws IOException {
        Request original = chain.request();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");

        String method = Objects.isNull(original.method()) ? "GET" : original.method().toUpperCase();
        // URL 取 path + query（不含 scheme/host），符合附件规范
        String urlPath = original.url().encodedPath();
        String query = original.url().encodedQuery();
        String url = (Objects.isNull(query) || query.isEmpty()) ? urlPath : urlPath + "?" + query;
        String body = readRequestBody(original);

        String signString = SignStringBuilder.buildRequestSignString(method, url, timestamp, nonce, body);
        String sign = RsaSigner.sign(signString, bizPrivateKey);

        LOG.info("[BIZ-Sign] 请求加签 | 算法=SHA256withRSA 私钥={} method={} url={}", keyId(bizPrivateKey), method, url);
        LOG.info("[BIZ-Sign] 签名串(5行)={}", replaceNewline(signString));
        LOG.info("[BIZ-Sign] 签名值={}", sign);
        LOG.info("[BIZ-Sign] 注入头 X-Pay-Timestamp={} X-Pay-Nonce={} X-Pay-Sign=<{}字符> X-Pay-App-Key={}", timestamp, nonce, sign.length(), appKey);

        LAST_SIGN.set(new SignContext(method, url, timestamp, nonce, sign, signString));

        Request signed = original.newBuilder()
                .addHeader(SignConstants.X_PAY_TIMESTAMP, timestamp)
                .addHeader(SignConstants.X_PAY_NONCE, nonce)
                .addHeader(SignConstants.X_PAY_SIGN, sign)
                .addHeader(SignConstants.X_PAY_APP_KEY, appKey)
                .build();
        return chain.proceed(signed);
    }

    /**
     * 读取 OkHttp 请求体原文，GET 请求或无 body 返回空串.
     * 通过 {@link Buffer} 复制一份，避免消费原始请求体流。
     */
    /**
     * 获取当前线程最近一次加签详情（请求结束后调用）.
     *
     * @return 加签上下文，无则 null
     */
    public static SignContext lastSignContext() {
        return LAST_SIGN.get();
    }

    /**
     * 清理当前线程的加签上下文（请求结束后调用，防内存泄漏）.
     */
    public static void clearSignContext() {
        LAST_SIGN.remove();
    }

    private String readRequestBody(final Request request) throws IOException {
        RequestBody body = request.body();
        if (Objects.isNull(body)) {
            return "";
        }
        MediaType contentType = body.contentType();
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }

    /**
     * 密钥标识（算法 + 后8位指纹），用于日志区分用哪个私钥加签，不泄露完整密钥.
     *
     * @param key 密钥
     * @return 算法 + 指纹片段
     */
    private static String keyId(final java.security.Key key) {
        byte[] enc = key.getEncoded();
        int len = Objects.isNull(enc) ? 0 : enc.length;
        String tail = len == 0 ? "?" : toHex(enc[len - 4]) + toHex(enc[len - 3]) + toHex(enc[len - 2]) + toHex(enc[len - 1]);
        return key.getAlgorithm() + "(" + (len / 1024 > 0 ? "RSA" : "?") + ".." + tail + ")";
    }

    /**
     * 把签名串里的换行符可视化，便于单行日志阅读.
     *
     * @param s 原始签名串
     * @return \n 被替换为 ↩ 的可视化串
     */
    private static String replaceNewline(final String s) {
        return s.replace("\n", "↩");
    }

    private static String toHex(final byte b) {
        return String.format("%02X", b);
    }

    /**
     * SignContext.
     *
     * <p>一次加签的完整上下文，供调用方展示加签过程。
     */
    public static final class SignContext {

        private final String method;

        private final String url;

        private final String timestamp;

        private final String nonce;

        private final String sign;

        private final String signString;

        SignContext(final String method, final String url, final String timestamp,
                    final String nonce, final String sign, final String signString) {
            this.method = method;
            this.url = url;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.sign = sign;
            this.signString = signString;
        }

        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
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

        public String getSignString() {
            return signString;
        }
    }
}
