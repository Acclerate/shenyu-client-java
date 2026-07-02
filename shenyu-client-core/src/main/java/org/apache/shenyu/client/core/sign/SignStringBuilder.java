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

import java.util.Objects;

/**
 * SignStringBuilder.
 *
 * <p>待签名串构造器，严格遵循支付服务（微信支付 V3 风格）规范。所有方法返回的字符串
 * 每行均以 {@code \n}（ASCII 0x0A）结尾，<b>包括最后一行</b>。
 *
 * <h3>一、请求加签（5 行格式）</h3>
 * <pre>
 * HTTP请求方法\n
 * URL\n
 * 请求时间戳\n
 * 请求随机串\n
 * 请求报文主体\n
 * </pre>
 * 规则要点（对应附件示例一~四）：
 * <ul>
 *   <li>请求方法大写：{@code POST} / {@code GET}</li>
 *   <li>URL 为绝对路径，<b>不含 scheme/host</b>；GET 请求需<b>包含完整 query string</b>（示例三）</li>
 *   <li>请求报文主体：POST 为 JSON body；GET 该行为空（仍保留一个 {@code \n}）</li>
 * </ul>
 *
 * <h3>二、响应/回调验签（3 行格式）</h3>
 * <pre>
 * 应答时间戳\n
 * 应答随机串\n
 * 应答报文主体\n
 * </pre>
 * 若应答报文主体为空（如 HTTP 204），最后一行仅为一个 {@code \n}。
 */
public final class SignStringBuilder {

    private SignStringBuilder() {
    }

    /**
     * 构造<b>请求加签</b>的待签名串（5 行格式）.
     *
     * @param method    HTTP 请求方法，如 {@code POST} / {@code GET}（建议大写）
     * @param url       请求 URL 的绝对路径部分，GET 请求需含 query string，例如
     *                  {@code /v3/pay/transactions/jsapi} 或
     *                  {@code /v3/marketing/partnerships?limit=5&offset=10}。不含 scheme/host。
     * @param timestamp 请求时间戳（秒级，字符串）
     * @param nonce     请求随机串
     * @param body      请求报文主体；GET 请求传 {@code null} 或空串
     * @return 待签名串，5 行均以 {@code \n} 结尾
     */
    public static String buildRequestSignString(final String method, final String url,
                                                final String timestamp, final String nonce,
                                                final String body) {
        String safeBody = Objects.isNull(body) ? "" : body;
        StringBuilder sb = new StringBuilder(64 + safeBody.length());
        sb.append(method).append(SignConstants.SIGN_DELIMITER)
                .append(url).append(SignConstants.SIGN_DELIMITER)
                .append(timestamp).append(SignConstants.SIGN_DELIMITER)
                .append(nonce).append(SignConstants.SIGN_DELIMITER)
                .append(safeBody).append(SignConstants.SIGN_DELIMITER);
        return sb.toString();
    }

    /**
     * 构造<b>响应验签 / 回调验签</b>的待签名串（3 行格式）.
     *
     * @param timestamp 应答/回调时间戳
     * @param nonce     应答/回调随机串
     * @param body      应答/回调报文主体；为空时最后一行仍保留一个 {@code \n}
     * @return 待签名串，3 行均以 {@code \n} 结尾
     */
    public static String buildResponseSignString(final String timestamp, final String nonce,
                                                 final String body) {
        String safeBody = Objects.isNull(body) ? "" : body;
        StringBuilder sb = new StringBuilder(64 + safeBody.length());
        sb.append(timestamp).append(SignConstants.SIGN_DELIMITER)
                .append(nonce).append(SignConstants.SIGN_DELIMITER)
                .append(safeBody).append(SignConstants.SIGN_DELIMITER);
        return sb.toString();
    }
}
