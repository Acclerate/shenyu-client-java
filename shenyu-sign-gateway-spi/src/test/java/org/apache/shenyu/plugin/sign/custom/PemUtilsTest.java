package org.apache.shenyu.plugin.sign.custom;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PemUtils} 单元测试。
 *
 * <p>纯函数测试，仅需 JUnit5 + JDK 自带类。
 */
class PemUtilsTest {

    /** 生成一个 RSA-2048 公钥的标准 PEM（含 BEGIN/END 标记、64 字符折行） */
    private static String generatePem() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        final KeyPair kp = kpg.generateKeyPair();
        return toPem(kp.getPublic());
    }

    /** X.509 公钥 → 标准 PEM 文本（64 字符折行） */
    static String toPem(final PublicKey publicKey) {
        final byte[] der = publicKey.getEncoded();
        final String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    @Test
    void parsePemStandardFormat() throws Exception {
        final String pem = generatePem();
        final PublicKey key = PemUtils.parsePem(pem);
        assertNotNull(key);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    void parsePemCompatibleWithCrlf() throws Exception {
        final String pem = generatePem().replace("\n", "\r\n");
        final PublicKey key = PemUtils.parsePem(pem);
        assertNotNull(key);
    }

    @Test
    void parsePemCompatibleWithNoNewlines() throws Exception {
        // 构造"标记无换行紧贴 base64"的紧凑 PEM，验证 decodeDer 能正确剥标记
        final String pem = generatePem();
        // 只去掉标记和 base64 之间的换行，保留标记本身的可识别性
        final String compact = pem.replace("-----BEGIN PUBLIC KEY-----\n", "-----BEGIN PUBLIC KEY-----")
                .replace("\n-----END PUBLIC KEY-----", "-----END PUBLIC KEY-----")
                .replaceAll("\n", "");
        final PublicKey key = PemUtils.parsePem(compact);
        assertNotNull(key);
    }

    @Test
    void fingerprintIsStableForSamePem() throws Exception {
        final String pem = generatePem();
        final String fp1 = PemUtils.fingerprintOf(pem);
        final String fp2 = PemUtils.fingerprintOf(pem);
        assertEquals(fp1, fp2, "同一 PEM 指纹应稳定");
    }

    @Test
    void fingerprintDistinguishesDifferentKeys() throws Exception {
        final String pem1 = generatePem();
        final String pem2 = generatePem();
        final String fp1 = PemUtils.fingerprintOf(pem1);
        final String fp2 = PemUtils.fingerprintOf(pem2);
        assertNotEquals(fp1, fp2, "不同公钥指纹应不同");
    }

    @Test
    void fingerprintIgnoresWhitespaceDifference() throws Exception {
        final String pemLf = generatePem();
        final String pemCrlf = pemLf.replace("\n", "\r\n");
        assertEquals(PemUtils.fingerprintOf(pemLf), PemUtils.fingerprintOf(pemCrlf),
                "LF 与 CRLF 的指纹应一致");
    }

    @Test
    void parsePemThrowsForInvalidContent() {
        assertThrows(Exception.class, () -> PemUtils.parsePem("not a pem"));
    }

    @Test
    void parsePemThrowsForNull() {
        assertThrows(Exception.class, () -> PemUtils.parsePem(null));
    }
}
