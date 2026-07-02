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

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RsaSignerTest.
 *
 * <p>两组用例：
 * <ol>
 *   <li>round-trip：运行时生成 RSA2048 密钥对 → 加签 → 验签（通过/篡改各一）</li>
 *   <li>【确定性锚点 / 跨工具一致性】使用 OpenSSL 生成的真实密钥对 + 真实签名值做验签，
 *       证明本实现与 OpenSSL 标准实现完全等价（OpenSSL 自验为 "Verified OK"，
 *       Java 端 {@link RsaSigner#verify} 同样通过）。签名串格式与微信支付 V3 响应验签完全一致。</li>
 * </ol>
 *
 * <p>注：需求文档示例公开的公钥与签名值经 OpenSSL 验证为 Verification failure（两者非同一密钥对
 * 产生），故未直接采用文档数据，改用可复现的 OpenSSL 真实匹配数据作为锚点。
 */
class RsaSignerTest {

    @Test
    void shouldSignAndVerifyRoundTripSuccessfully() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        String signString = SignStringBuilder.buildRequestSignString(
                "POST", "/v3/pay/transactions/jsapi", "1554208460",
                "593BEC0C930BF1AFEB40B4A08C8FB242", "{\"appid\":\"wx123\"}");
        String signature = RsaSigner.sign(signString, privateKey);

        assertTrue(RsaSigner.verify(signString, signature, publicKey),
                "round-trip 加签后验签必须通过");
    }

    @Test
    void shouldFailVerifyWhenSignStringTampered() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String signString = "POST\n/v3/pay\nts\nnonce\nbody\n";
        String signature = RsaSigner.sign(signString, keyPair.getPrivate());

        String tampered = "POST\n/v3/pay\nts\nnonce\nTAMPERED\n";
        assertFalse(RsaSigner.verify(tampered, signature, keyPair.getPublic()),
                "签名串被篡改后验签必须失败");
    }

    /**
     * 【确定性锚点 / 跨工具一致性】使用 OpenSSL 生成的 RSA2048 密钥对 + 真实签名值做验签.
     *
     * <p>验证本实现的 {@code verify} 与 OpenSSL 标准实现完全等价。生成方式（命令行）：
     * <pre>
     * openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out anchor_priv.pem
     * openssl rsa -in anchor_priv.pem -pubout -out anchor_pub.pem
     * printf '%s\n%s\n%s\n' '1722850421' 'd824f2e086d3c1df967785d13fcd22ef' \
     *   '{"code_url":"weixin://wxpay/bizpayurl?pr=JyC91EIz1"}' &gt; data.txt
     * openssl dgst -sha256 -sign anchor_priv.pem -out sig.bin data.txt   # OpenSSL 自验 Verified OK
     * base64 -w0 sig.bin                                                  # 得到下方 signature
     * </pre>
     * 同一组数据经 OpenSSL {@code dgst -sha256 -verify} 确认为 "Verified OK"，
     * 本测试断言 Java 端 {@link RsaSigner#verify} 同样通过。
     *
     * <p>注：需求文档示例中公开的公钥与签名值并非同一密钥对产生（OpenSSL 直接验也为
     * Verification failure），故此处改用自生成的真实匹配数据作为可复现的锚点；
     * 签名串格式仍与文档完全一致（3 行 {@code \n} 结尾）。
     */
    @Test
    void shouldVerifyOpenSslGeneratedSample() {
        // OpenSSL 生成的公钥（anchor_pub.pem）
        String pubPem = "-----BEGIN PUBLIC KEY-----\n"
                + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAq9NPVu/rcL3+TFGyBfAi\n"
                + "ymKlsDMocx+awBj9Rku9qpZucFK4PBUepPgKebzDKuVDq3vWW400hRY5evg4es2z\n"
                + "DA8UPxiJAf7kSQ5iT3skRefzdaSNyTs8MmfG4ZxKT+tJR3Vy9Ow6leoepMI9Y9Mf\n"
                + "yHeM/EKm2+72JH7H1TVD2a0d0XgvwyZWzAGGmauJjcpsqcD1uFtcwGlfmn5uBUjt\n"
                + "aw/dtMZ1POHBFcG4khNUl2oehUiu8XsCRWrcobZeDjS1AOHTMBe9Jts4G/F1yPzN\n"
                + "R/vxygKHcVVjkoLV4qxedH8RRwFzgoI2JRh9KZ6QgE/mslxJ6kTaO6EZTg2mTDvD\n"
                + "uQIDAQAB\n"
                + "-----END PUBLIC KEY-----";
        PublicKey publicKey = PemUtils.loadPublicKeyFromPem(pubPem);

        // 验签串（与微信支付 V3 响应验签 3 行格式完全一致）
        String timestamp = "1722850421";
        String nonce = "d824f2e086d3c1df967785d13fcd22ef";
        String body = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=JyC91EIz1\"}";
        String signString = SignStringBuilder.buildResponseSignString(timestamp, nonce, body);

        // OpenSSL 用对应私钥生成的真实签名值（Base64）
        String signature = "S8M+kGSU2WIgONwcVXKIG3NCcch0Xe51JaaHeWH8x7O4FQugQuVi4yVK7e1KEbvy8cjzlsPYB8cELKTmt72rkMmRP7L91H8d7TaeEPotEL+BZBl1N2o2HXF0nQq/IgWwQgzppdyZ"
                + "7aWPmYLBBfhuN9Ok7dZ1Le310r2PIaLcJF9FLNw99arOUF/d0otwAP85iWu2wld9I+SpxTDO3nH7pzWM33d5BuoNZjI6LcfBPgmTkYgMrviQQ4tV1gMo9hFvv4AFVwl206MYd/J0VQcZ"
                + "k1OZO1r5yjRYHXzfJmBHPCH+dJFCFJlPNoZkqZjX1Nrto+dyXgtCQQmJ9OojlPrIIw==";

        assertTrue(RsaSigner.verify(signString, signature, publicKey),
                "与 OpenSSL 生成的真实数据验签必须通过（Verified OK）");
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
