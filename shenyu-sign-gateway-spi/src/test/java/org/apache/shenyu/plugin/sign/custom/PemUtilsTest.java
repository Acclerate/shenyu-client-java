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
 *
 * <p>被测契约：PemUtils 只接受<b>裸 Base64</b>公钥（无 PEM 头尾标记）。
 */
class PemUtilsTest {

    /** 生成一个 RSA-2048 公钥的裸 Base64 字符串（64 字符折行） */
    private static String generateBase64() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        final KeyPair kp = kpg.generateKeyPair();
        return toBase64(kp.getPublic());
    }

    /** X.509 公钥 → 裸 Base64 文本（64 字符折行，不含 PEM 头尾标记） */
    static String toBase64(final PublicKey publicKey) {
        final byte[] der = publicKey.getEncoded();
        return Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
    }

    @Test
    void parseBase64StandardFormat() throws Exception {
        final String base64 = generateBase64();
        final PublicKey key = PemUtils.parsePem(base64);
        assertNotNull(key);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    void parseBase64CompatibleWithCrlf() throws Exception {
        final String base64 = generateBase64().replace("\n", "\r\n");
        final PublicKey key = PemUtils.parsePem(base64);
        assertNotNull(key);
    }

    @Test
    void parseBase64CompatibleWithNoNewlines() throws Exception {
        // 构造无换行紧贴的裸 Base64，验证 decodeDer 能正确去空白后解码
        final String base64 = generateBase64().replaceAll("\\s", "");
        final PublicKey key = PemUtils.parsePem(base64);
        assertNotNull(key);
    }

    @Test
    void fingerprintIsStableForSameKey() throws Exception {
        final String base64 = generateBase64();
        final String fp1 = PemUtils.fingerprintOf(base64);
        final String fp2 = PemUtils.fingerprintOf(base64);
        assertEquals(fp1, fp2, "同一公钥指纹应稳定");
    }

    @Test
    void fingerprintDistinguishesDifferentKeys() throws Exception {
        final String base64_1 = generateBase64();
        final String base64_2 = generateBase64();
        final String fp1 = PemUtils.fingerprintOf(base64_1);
        final String fp2 = PemUtils.fingerprintOf(base64_2);
        assertNotEquals(fp1, fp2, "不同公钥指纹应不同");
    }

    @Test
    void fingerprintIgnoresWhitespaceDifference() throws Exception {
        final String lf = generateBase64();
        final String crlf = lf.replace("\n", "\r\n");
        assertEquals(PemUtils.fingerprintOf(lf), PemUtils.fingerprintOf(crlf),
                "LF 与 CRLF 的指纹应一致");
    }

    @Test
    void parseThrowsForInvalidContent() {
        assertThrows(Exception.class, () -> PemUtils.parsePem("not a base64 key"));
    }

    @Test
    void parseThrowsForNull() {
        assertThrows(Exception.class, () -> PemUtils.parsePem(null));
    }
}
