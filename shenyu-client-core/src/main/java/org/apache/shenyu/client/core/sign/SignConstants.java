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

/**
 * SignConstants.
 *
 * <p>支付服务（微信支付 V3 风格）加签验签协议常量。涵盖：
 * <ul>
 *   <li>HTTP 头名称：{@code X-Pay-Timestamp} / {@code X-Pay-Nonce} / {@code X-Pay-Sign}</li>
 *   <li>签名算法：{@code SHA256withRSA}（标准 JCA 名称）</li>
 *   <li>待签名串分隔符：{@code \n}（ASCII 0x0A）</li>
 * </ul>
 *
 * <p>本常量集独立于 {@code Constants} 中 ShenYu 原生 sign 插件协议常量，
 * 两者互不干扰（原生为 AES 对称 + ShenYu-Authorization 头，本套为 RSA 非对称 + X-Pay-* 头）。
 */
public final class SignConstants {

    /**
     * HTTP 头：请求/应答时间戳（毫秒级，13 位整数字符串）.
     */
    public static final String X_PAY_TIMESTAMP = "X-Pay-Timestamp";

    /**
     * HTTP 头：请求/应答随机串（32 位 hex）.
     */
    public static final String X_PAY_NONCE = "X-Pay-Nonce";

    /**
     * HTTP 头：签名值（Base64 编码）.
     */
    public static final String X_PAY_SIGN = "X-Pay-Sign";

    /**
     * 签名算法 SHA256 with RSA，JDK8 原生 {@code Signature.getInstance("SHA256withRSA")} 即可支持.
     */
    public static final String SHA256_WITH_RSA = "SHA256withRSA";

    /**
     * 待签名串行分隔符换行符（ASCII 0x0A），每行（含最后一行）结尾均需附加.
     */
    public static final String SIGN_DELIMITER = "\n";

    /**
     * 时间戳允许的时间偏差（秒），用于防重放，默认 ±5 分钟.
     */
    public static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300L;

    private SignConstants() {
    }
}
