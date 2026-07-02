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

package org.apache.shenyu.demo.sign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PaySignProperties.
 *
 * <p>支付加签验签 Demo 配置，绑定 {@code pay.sign.*}。
 */
@ConfigurationProperties(prefix = "pay.sign")
public class PaySignProperties {

    /**
     * 业务系统私钥路径（classpath: 或 file: 前缀，加签出站请求）。
     */
    private String bizPrivateKey;

    /**
     * 业务系统公钥路径（支付服务持有，验签入站请求）。
     */
    private String bizPublicKey;

    /**
     * 支付服务私钥路径（加签响应/回调）。
     */
    private String payPrivateKey;

    /**
     * 支付服务公钥路径（业务系统持有，验签响应/回调）。
     */
    private String payPublicKey;

    /**
     * 时间戳防重放允许偏差（秒）。
     */
    private long timestampToleranceSeconds = 300L;

    /**
     * 支付服务基础地址。
     */
    private String payBaseUrl;

    /**
     * 业务系统回调接收地址。
     */
    private String bizCallbackUrl;

    public String getBizPrivateKey() {
        return bizPrivateKey;
    }

    public void setBizPrivateKey(final String bizPrivateKey) {
        this.bizPrivateKey = bizPrivateKey;
    }

    public String getBizPublicKey() {
        return bizPublicKey;
    }

    public void setBizPublicKey(final String bizPublicKey) {
        this.bizPublicKey = bizPublicKey;
    }

    public String getPayPrivateKey() {
        return payPrivateKey;
    }

    public void setPayPrivateKey(final String payPrivateKey) {
        this.payPrivateKey = payPrivateKey;
    }

    public String getPayPublicKey() {
        return payPublicKey;
    }

    public void setPayPublicKey(final String payPublicKey) {
        this.payPublicKey = payPublicKey;
    }

    public long getTimestampToleranceSeconds() {
        return timestampToleranceSeconds;
    }

    public void setTimestampToleranceSeconds(final long timestampToleranceSeconds) {
        this.timestampToleranceSeconds = timestampToleranceSeconds;
    }

    public String getPayBaseUrl() {
        return payBaseUrl;
    }

    public void setPayBaseUrl(final String payBaseUrl) {
        this.payBaseUrl = payBaseUrl;
    }

    public String getBizCallbackUrl() {
        return bizCallbackUrl;
    }

    public void setBizCallbackUrl(final String bizCallbackUrl) {
        this.bizCallbackUrl = bizCallbackUrl;
    }
}
