/*
 * KeyPairConsistencyTest —— 验证 biz-private-key.pem 与 admin YYT 公钥是否配对。
 *
 * 用私钥签一段已知串，分别用两个公钥验签：
 *   1. classpath 的 biz-public-key.pem
 *   2. admin app_auth 里 YYT 的裸 Base64 公钥（硬编码，来自数据库）
 * 如果两个都验过，说明配对正确；问题在别处。
 * 如果只有 1 验过、2 失败，说明 admin 数据或网关解析逻辑有问题。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeyPairConsistencyTest {

    /** admin app_auth 里 YYT 的 app_secret（裸 Base64，无 PEM 标记），来自数据库直查 */
    private static final String YYT_RAW_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAw+GxZICU3OS+OWrIbuCA"
        + "pzuHQTvmIZjpBbUvIdcFBDHL/jFACbNBPjNK9bnaHwjJrPwWFAcVaGaAyCcvgMCR"
        + "rK7ha5V9L9MQbThirAU/53j3oZyoinY+zUpHUuNRGjD+ByDsrKIfWnASH/zTJmKR"
        + "0f9VF0TnUpo03ljKkosT6nqg7FD9PF98YqQK3HlbOC4H7rclbP81cjRePxi+MFU3"
        + "GetPC46ZZewsOCse4nDWhXv8YRFe9aIyDanqIdm9VE5SLfqtDtL7RY5f1UW25xB/"
        + "jwx/rNO9hFCcp0xQXC0xqkjQ2IEgfVcLH3GofjdBdgrpsT0F5MC0GVXapdkH5fAt"
        + "gQIDAQAB";

    @Test
    public void keyPairShouldMatch() throws Exception {
        String privPem = PaySignUtils.readResourceUtf8("/keys/biz-private-key.pem");
        PrivateKey privateKey = PaySignUtils.loadPrivateKey(privPem);

        String testString = "GET\n/test\n123\nnonce\nbody\n";
        String sign = PaySignUtils.sign(testString, privateKey);

        // 公钥 1：classpath biz-public-key.pem（PEM 格式）
        String pubPem = PaySignUtils.readResourceUtf8("/keys/biz-public-key.pem");
        // 注意：test/resources 只有 biz-private-key.pem 和 pay-public-key.pem
        // biz-public-key.pem 在 main/resources，测试 classpath 可能拿不到，用裸 base64 包成 PEM
        String pubPemWrapped = "-----BEGIN PUBLIC KEY-----\n"
                + YYT_RAW_BASE64 + "\n-----END PUBLIC KEY-----\n";
        PublicKey pubFromPem = PaySignUtils.loadPublicKey(pubPemWrapped);
        boolean verify1 = PaySignUtils.verify(testString, sign, pubFromPem);
        System.out.println("公钥1 (YYT 裸 Base64 包成 PEM) 验签: " + (verify1 ? "PASS" : "FAIL"));

        // 公钥 2：YYT 裸 Base64 直接交给 PemUtils.parsePem（模拟网关 SignCacheBizPublicKeyProvider 的调用）
        // 网关用的是 org.apache.shenyu.plugin.sign.custom.PemUtils，逻辑：replace BEGIN/END + 去空白 + Base64 解码
        // 裸 Base64 没有 BEGIN/END，replace 无效，去空白后 Base64 解码——应等价
        PublicKey pubFromRaw = PaySignUtils.loadPublicKey(YYT_RAW_BASE64);
        boolean verify2 = PaySignUtils.verify(testString, sign, pubFromRaw);
        System.out.println("公钥2 (YYT 裸 Base64 直接解析) 验签: " + (verify2 ? "PASS" : "FAIL"));

        assertTrue(verify1, "biz-private-key 与 YYT 公钥(包PEM) 不配对");
        assertTrue(verify2, "biz-private-key 与 YYT 公钥(裸Base64) 不配对");
    }
}
