/*
 * PemUtils —— 裸 Base64 公钥解析与 fingerprint 计算工具。
 */
package org.apache.shenyu.plugin.sign.custom;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 公钥处理工具（X.509 RSA）。
 *
 * <p>本工具只接受<b>裸 Base64</b>公钥字符串（X.509 SubjectPublicKeyInfo 的 Base64 编码），
 * 不接受带 {@code -----BEGIN/END PUBLIC KEY-----} 头尾标记的 PEM 文本。
 * 生产侧（erpm-pay-center {@code ShenyuKeyPushService.normalizeToBase64}）在推送前
 * 已统一剥离 PEM 标记并写入 {@code app_auth.app_secret}。
 */
final class PemUtils {

    private static final String SHA256_PREFIX = "sha256:";

    private PemUtils() {
    }

    /**
     * 裸 Base64 公钥字符串 → RSA PublicKey。
     *
     * <p>兼容 LF / CRLF / 无换行三种排版（统一清空白）。
     *
     * @param base64PublicKey 裸 Base64 公钥字符串（不含 PEM 头尾标记）
     * @return RSA PublicKey
     * @throws Exception 解析失败
     */
    static PublicKey parsePem(final String base64PublicKey) throws Exception {
        final byte[] der = decodeDer(base64PublicKey);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * 计算公钥 fingerprint（Base64 → DER → SHA-256 → {@code sha256:<hex>}）。
     *
     * @param base64PublicKey 裸 Base64 公钥字符串
     * @return 形如 {@code sha256:a1b2c3...} 的 64 位 hex 指纹
     * @throws Exception 解析失败
     */
    static String fingerprintOf(final String base64PublicKey) throws Exception {
        final byte[] der = decodeDer(base64PublicKey);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        final StringBuilder sb = new StringBuilder(digest.length * 2 + SHA256_PREFIX.length())
                .append(SHA256_PREFIX);
        for (final byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 去除所有空白后将裸 Base64 解码为 DER 字节 */
    private static byte[] decodeDer(final String base64PublicKey) {
        final String base64 = base64PublicKey.replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
