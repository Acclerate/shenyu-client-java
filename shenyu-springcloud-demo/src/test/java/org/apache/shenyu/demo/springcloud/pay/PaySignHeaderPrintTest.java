/*
 * 一键打印「pay/url/create」接口当前最新请求头（用于交给业务方在 Apifox 直接调用）。
 *
 * 与 PaySignSuccessExampleA 的区别：本类【只生成并控制台打印请求头/请求体】，不发起真实 HTTP 请求，
 * 也不做断言，专为「让业务方拿到一组当前时间戳、可直连网关验签通过的头」而生。
 *
 * 运行：
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignHeaderPrintTest
 *
 * 说明：
 *   - 私钥取 appKey=06 对应的生产私钥：classpath 资源 /keys/biz-private-key-online-06.pem
 *   - 网关验签时间窗 ±300s，打印出的头约 5 分钟内有效，过期重跑本测试即可。
 *   - 请求体为与签名逐字节绑定的 pretty 体（与网关已验证通过的字节一致），切勿改动格式后再发。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

public class PaySignHeaderPrintTest {

    private static final String GATEWAY = "https://shenyu.dev.jztweb.com";
    private static final String PATH = "/payCenter/v1/pay/url/create";
    private static final String METHOD = "POST";
    private static final String APP_KEY = "06";
    private static final String PRIVATE_KEY_RESOURCE = "/keys/biz-private-key-online1.pem";

    /** 与签名逐字节绑定的请求体（pretty 体，网关已验证接受；改动任何空格/换行都会导致 401） */
    private static final String BODY = "{\n"
            + "  \"requestSerialNo\" : \"FDGDSD20260000600002\",\n"
            + "  \"bizOrderNo\" : \"FDGDSD202600006\",\n"
            + "  \"goodsDesc\" : \"上下游辅助系统：代收单\",\n"
            + "  \"amount\" : 50,\n"
            + "  \"buyerName\" : \"黄金梅利\",\n"
            + "  \"buyerUniqueId\" : \"0000032183G00001\",\n"
            + "  \"makerName\" : \"黄金梅利\"\n"
            + "}";

    @Test
    public void printHeaders() throws Exception {
        // 1) 加载 appKey=06 生产私钥
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource(PRIVATE_KEY_RESOURCE);

        // 2) 当前时间戳 + nonce + 按 body 加签
        String ts = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String signString = PaySignUtils.buildRequestSignString(METHOD, PATH, ts, nonce, BODY);
        String sign = PaySignUtils.sign(signString, pk);

        // 3) 本地自验：由私钥导公钥 verify 一遍，确认 sign 与 body 绑定正确
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        boolean selfVerify = PaySignUtils.verify(signString, sign, pub);
        boolean validB64 = (sign.length() % 4 == 0) && java.util.Base64.getDecoder().decode(sign).length == 256;

        // ===== 控制台输出 =====
        System.out.println("============================================================");
        System.out.println("  pay/url/create 最新请求头（当前时间戳，约 5 分钟有效）");
        System.out.println("============================================================");
        System.out.println();
        System.out.println("【请求 URL】");
        System.out.println("  POST " + GATEWAY + PATH);
        System.out.println();
        System.out.println("【请求头（4 个签名头 + 2 个固定头，直接整段复制）】");
        System.out.println("X-Pay-App-Key: " + APP_KEY);
        System.out.println("X-Pay-Timestamp: " + ts);
        System.out.println("X-Pay-Nonce: " + nonce);
        System.out.println("X-Pay-Sign: " + sign);
        System.out.println("Content-type: application/json; charset=utf-8");
        System.out.println("Accept: application/json");
        System.out.println();
        System.out.println("【请求体（Body · raw，原样发送，禁止格式化）】");
        System.out.println(BODY);
        System.out.println();
        System.out.println("【自检】SELF_VERIFY=" + selfVerify + "  SIGN_LEN=" + sign.length()
                + "  VALID_BASE64_256B=" + validB64);
        System.out.println("============================================================");

        if (!selfVerify || !validB64) {
            System.out.println("⚠️ 自检未通过，请勿使用该组头发起请求！");
        }
    }
}
