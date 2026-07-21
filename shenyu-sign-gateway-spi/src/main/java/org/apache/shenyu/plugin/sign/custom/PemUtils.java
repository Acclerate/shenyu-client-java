/*
 * PemUtils —— PEM 公钥解析与 fingerprint 计算工具。
 */
package org.apache.shenyu.plugin.sign.custom;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM 公钥处理工具（X.509 RSA）。
 */
final class PemUtils {

    private static final String BEGIN_MARKER = "-----BEGIN PUBLIC KEY-----";

    private static final String END_MARKER = "-----END PUBLIC KEY-----";

    private static final String SHA256_PREFIX = "sha256:";

    private PemUtils() {
    }

    /**
     * PEM 字符串 → RSA PublicKey。
     *
     * <p>兼容 LF / CRLF / 无换行 三种 PEM 格式（统一清空白）。
     *
     * @param pem 完整 PEM 字符串（含 BEGIN/END 标记）
     * @return RSA PublicKey
     * @throws Exception PEM 解析失败
     */
    static PublicKey parsePem(final String pem) throws Exception {
        final byte[] der = decodeDer(pem);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * 计算公钥 fingerprint（PEM → DER → SHA-256 → {@code sha256:<hex>}）。
     *
     * @param pem 完整 PEM 字符串
     * @return 形如 {@code sha256:a1b2c3...} 的 64 位 hex 指纹
     * @throws Exception PEM 解析失败
     */
    static String fingerprintOf(final String pem) throws Exception {
        final byte[] der = decodeDer(pem);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        final StringBuilder sb = new StringBuilder(digest.length * 2 + SHA256_PREFIX.length())
                .append(SHA256_PREFIX);
        for (final byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 剥掉 PEM 头尾标记 + 所有空白，Base64 解码为 DER 字节 */
    private static byte[] decodeDer(final String pem) {
        final String base64 = pem.replace(BEGIN_MARKER, "")
                .replace(END_MARKER, "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
