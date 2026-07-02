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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * RsaSigner.
 *
 * <p>RSA-SHA256 加签与验签工具，使用标准 JCA（JDK8 原生支持，无需 BouncyCastle）。
 *
 * <h3>加签流程（对应附件「计算签名值」）</h3>
 * <ol>
 *   <li>{@link Signature#getInstance(String)} ({@code SHA256withRSA})</li>
 *   <li>{@link Signature#initSign(PrivateKey)}</li>
 *   <li>{@link Signature#update(byte[])} 待签名串（UTF-8）</li>
 *   <li>{@link Signature#sign()} → Base64 编码（标准编码，非 URL safe）</li>
 * </ol>
 *
 * <h3>验签流程（对应附件「验证签名」）</h3>
 * <ol>
 *   <li>Base64 解码签名值</li>
 *   <li>{@link Signature#initVerify(PublicKey)}</li>
 *   <li>{@link Signature#update(byte[])} 待签名串（UTF-8）</li>
 *   <li>{@link Signature#verify(byte[])} → {@code true} 表示验签通过</li>
 * </ol>
 *
 * <p>异常风格与 {@code AesUtils} 一致。
 */
public final class RsaSigner {

    private static final Logger LOG = LoggerFactory.getLogger(RsaSigner.class);

    private RsaSigner() {
    }

    /**
     * 使用私钥对待签名串进行 SHA256withRSA 签名，返回 Base64 编码的签名值.
     *
     * @param signString 待签名串（由 {@link SignStringBuilder} 构造）
     * @param privateKey 私钥
     * @return Base64 编码的签名值
     */
    public static String sign(final String signString, final PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SignConstants.SHA256_WITH_RSA);
            signature.initSign(privateKey);
            signature.update(signString.getBytes(StandardCharsets.UTF_8));
            byte[] signed = signature.sign();
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            LOG.error("rsa sign fail. cause:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用公钥对待签名串与签名值进行 SHA256withRSA 验签.
     *
     * @param signString     待签名串（由 {@link SignStringBuilder} 构造）
     * @param base64Signature Base64 编码的签名值
     * @param publicKey      公钥
     * @return {@code true} 验签通过；{@code false} 验签失败
     */
    public static boolean verify(final String signString, final String base64Signature,
                                 final PublicKey publicKey) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);
            Signature signature = Signature.getInstance(SignConstants.SHA256_WITH_RSA);
            signature.initVerify(publicKey);
            signature.update(signString.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            LOG.error("rsa verify fail. cause:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
