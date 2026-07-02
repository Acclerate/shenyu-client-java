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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * PemUtils.
 *
 * <p>PEM 格式密钥加载工具，支持 PKCS#8 私钥（{@code -----BEGIN PRIVATE KEY-----}）
 * 与 X.509 公钥（{@code -----BEGIN PUBLIC KEY-----}）。加载流程：
 * <ol>
 *   <li>剥离 PEM 头尾标记与换行</li>
 *   <li>Base64 解码为 DER 字节</li>
 *   <li>构造 {@link PKCS8EncodedKeySpec} / {@link X509EncodedKeySpec}</li>
 *   <li>{@link KeyFactory#getInstance(String)} (RSA) 生成密钥对象</li>
 * </ol>
 *
 * <p>异常风格与 {@code AesUtils} 一致：捕获通用 Exception，记录 SLF4J 日志后抛 RuntimeException。
 */
public final class PemUtils {

    private static final Logger LOG = LoggerFactory.getLogger(PemUtils.class);

    private static final String RSA = "RSA";

    private PemUtils() {
    }

    /**
     * 从 PEM 文本加载 PKCS#8 私钥.
     *
     * @param pem PEM 格式私钥文本（含 BEGIN/END 标记）
     * @return 私钥对象
     */
    public static PrivateKey loadPrivateKeyFromPem(final String pem) {
        try {
            byte[] der = decodePem(pem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            LOG.error("load private key from pem fail. cause:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 从 PEM 文本加载 X.509 公钥.
     *
     * @param pem PEM 格式公钥文本（含 BEGIN/END 标记）
     * @return 公钥对象
     */
    public static PublicKey loadPublicKeyFromPem(final String pem) {
        try {
            byte[] der = decodePem(pem);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(der);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            LOG.error("load public key from pem fail. cause:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 从 classpath 资源读取文本（用于 Demo 从 resources 加载 PEM）.
     *
     * @param classpathResource classpath 路径，如 {@code /keys/biz-private-key.pem}
     * @return 资源文本内容
     * @throws IOException 读取失败
     */
    public static String loadResourceAsString(final String classpathResource) throws IOException {
        try (InputStream in = PemUtils.class.getResourceAsStream(classpathResource)) {
            if (Objects.isNull(in)) {
                throw new IOException("classpath resource not found: " + classpathResource);
            }
            byte[] bytes = readAll(in);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 从文件系统路径读取文本.
     *
     * @param path 文件路径
     * @return 文件文本内容
     * @throws IOException 读取失败
     */
    public static String loadFileAsString(final String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /**
     * 剥离 PEM 头尾标记、换行、空白，并 Base64 解码为 DER 字节.
     *
     * @param pem PEM 文本
     * @return DER 字节
     */
    private static byte[] decodePem(final String pem) {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(cleaned);
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
