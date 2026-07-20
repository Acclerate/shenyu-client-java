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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务公钥内存存储。
 *
 * <p>启动时根据 {@link SignProperties} 的 appKey → PEM 文件名映射，从 classpath:keys/ 读取并解析，
 * 计算 SHA-256 fingerprint，存入内存 {@code Map<appKey, PublicKeyInfo>}。运行时 O(1) 读取。
 *
 * <p>该组件仅供网关 SPI 通过 demo 自身端口（默认 8470）直连调用，
 * <b>不</b>标 {@code @ShenyuSpringCloudClient}，因此不会被注册到 admin / 网关路由。
 */
@Component
public class PublicKeyStore {

    private static final Logger LOG = LoggerFactory.getLogger(PublicKeyStore.class);

    private final Map<String, PublicKeyInfo> store = new ConcurrentHashMap<>();

    public PublicKeyStore(final SignProperties props) {
        if (props.getKeys() != null) {
            for (Map.Entry<String, String> entry : props.getKeys().entrySet()) {
                try {
                    PublicKeyInfo info = load(entry.getKey(), entry.getValue());
                    store.put(entry.getKey(), info);
                    LOG.info("[demo-sign] 加载公钥成功 appKey={} fingerprint={}", entry.getKey(), info.getFingerprint());
                } catch (Exception e) {
                    LOG.error("[demo-sign] 加载公钥失败 appKey={} file={}: {}", entry.getKey(), entry.getValue(), e.getMessage());
                }
            }
        }
    }

    private PublicKeyInfo load(final String appKey, final String fileName) throws Exception {
        ClassPathResource resource = new ClassPathResource("keys/" + fileName);
        String pem;
        try (InputStream in = resource.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            pem = new String(out.toByteArray(), StandardCharsets.UTF_8);
            // 归一化换行符：Windows 下 openssl 生成的 PEM 是 CRLF，统一转 LF，避免跨平台差异与下游解析歧义
            pem = pem.replace("\r", "");
        }

        // 解析以便校验，并计算 fingerprint
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(der);
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        PublicKeyInfo info = new PublicKeyInfo();
        info.setAppKey(appKey);
        info.setPublicKey(pem.trim());
        info.setAlgorithm(publicKey.getAlgorithm());
        info.setFormat(publicKey.getFormat());
        info.setFingerprint(sb.toString());
        info.setRetrievedAt(System.currentTimeMillis());
        return info;
    }

    /**
     * 按 appKey 取公钥信息。
     *
     * @param appKey 应用标识
     * @return 公钥信息；不存在返回 null（调用方应返回 HTTP 404）
     */
    public PublicKeyInfo get(final String appKey) {
        return store.get(appKey);
    }
}
