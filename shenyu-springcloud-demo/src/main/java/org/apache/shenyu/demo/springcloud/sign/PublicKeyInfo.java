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

package org.apache.shenyu.demo.springcloud.sign;

/**
 * 业务方公钥信息（HTTP 接口返回体）。
 *
 * <p>成功响应含 {@code publicKey}（PEM 字符串）；错误响应 {@code error}/{@code message} 非空，
 * {@code publicKey} 为空。网关 SPI 端用 gson 解析取 {@code publicKey} 字段。
 */
public class PublicKeyInfo {

    private String appKey;

    private String publicKey;

    private String algorithm;

    private String format;

    private String fingerprint;

    private long retrievedAt;

    /** 错误码（成功时为 null）。例：app_key_not_found */
    private String error;

    /** 错误信息（成功时为 null） */
    private String message;

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(final String appKey) {
        this.appKey = appKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(final String publicKey) {
        this.publicKey = publicKey;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(final String algorithm) {
        this.algorithm = algorithm;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(final String format) {
        this.format = format;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(final String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public long getRetrievedAt() {
        return retrievedAt;
    }

    public void setRetrievedAt(final long retrievedAt) {
        this.retrievedAt = retrievedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(final String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    /**
     * 构造错误响应体（配合 HTTP 404 使用）。
     *
     * @param error     错误码
     * @param appKey    请求的应用标识
     * @param message   错误描述
     * @return 错误态的 PublicKeyInfo
     */
    public static PublicKeyInfo error(final String error, final String appKey, final String message) {
        PublicKeyInfo info = new PublicKeyInfo();
        info.error = error;
        info.appKey = appKey;
        info.message = message;
        return info;
    }
}
