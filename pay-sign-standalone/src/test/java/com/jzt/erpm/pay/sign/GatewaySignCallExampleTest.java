/*
 * 网关验签成功示例（pay-sign-standalone 内置，使用 OkHttp 调用线上网关）。
 *
 * 演示「业务系统发出支付请求前」如何用本模块给请求加签，并真正打到 shenyu 网关完成验签：
 *   1. 用 appKey=06 对应的生产私钥（classpath:/keys/biz-private-key-online-06.pem）加签；
 *   2. 签名串 = METHOD\nURL\nTS\nNONCE\nBODY\n（与网关 PayRsaSignService 完全一致）；
 *   3. 用 OkHttp 发送，body 逐字节原样发出（OkHttp 不做 JSON 格式化，故签名必然对齐）；
 *   4. 断言：只要不是 401 sign verify failed（即验签通过），即视为成功示例。
 *
 * 运行（需能直连内网域名 shenyu.dev.jztweb.com）：
 *   mvn -f pay-sign-standalone/pom.xml test -Dtest=GatewaySignCallExampleTest
 */
package com.jzt.erpm.pay.sign;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.TimeUnit;

public class GatewaySignCallExampleTest {

    private static final String GATEWAY = "https://shenyu.dev.jztweb.com";
    private static final String PATH = "/payCenter/v1/pay/queryByBizOrderNo";
//    private static final String PATH = "/payCenter/v1/pay/url/create";
    private static final String METHOD = "POST";
//    private static final String APP_KEY = "06";
//    private static final String PRIVATE_KEY_RESOURCE = "/keys/biz-private-key-online-06.pem"; //06的
    private static final String APP_KEY = "09";
    private static final String PRIVATE_KEY_RESOURCE = "/keys/pay_private_key_09.pem"; //06的

    /** 与签名逐字节绑定的请求体（pretty 体；OkHttp 原样发送，任何格式化都会破坏签名） */
    private static final String BODY = "{\n" +
            "    \"bizOrderNo\": \"SYD2082277494827913216\",\n" +
            "    \"payChannelRequestSerialNo\": \"2082277536900976640\",\n" +
            "    \"payRequestSerialNo\": null,\n" +
            "    \"requestSerialNo\": null,\n" +
            "    \"channelTxId\": null\n" +
            "  }";

    @Test
    public void callGatewayWithSign() throws Exception {
        // 1) 加载 appKey=06 生产私钥
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource(PRIVATE_KEY_RESOURCE);

        // 2) 当前时间戳 + nonce + 按 body 加签
        String ts = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String signString = PaySignUtils.buildRequestSignString(METHOD, PATH, ts, nonce, BODY);
        String sign = PaySignUtils.sign(signString, pk);

        // 3) 本地自验：私钥导公钥 verify 一遍，确认 sign 与 body 绑定正确
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        boolean selfVerify = PaySignUtils.verify(signString, sign, pub);
        boolean validB64 = (sign.length() % 4 == 0) && java.util.Base64.getDecoder().decode(sign).length == 256;

        // 4) 打印请求头（方便对照 / 排查）
        System.out.println("============================================================");
        System.out.println("  pay/url/create 网关调用（OkHttp + 当前时间戳）");
        System.out.println("============================================================");
        System.out.println("URL: " + GATEWAY + PATH);
        System.out.println("X-Pay-App-Key: " + APP_KEY);
        System.out.println("X-Pay-Timestamp: " + ts);
        System.out.println("X-Pay-Nonce: " + nonce);
        System.out.println("X-Pay-Sign: " + sign);
        System.out.println("Body:\n" + BODY);
        System.out.println("自检: SELF_VERIFY=" + selfVerify + "  SIGN_LEN=" + sign.length()
                + "  VALID_BASE64_256B=" + validB64);
        System.out.println("============================================================");

        // 5) 用 OkHttp 调用网关（body 逐字节原样发出）
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(GATEWAY + PATH)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), BODY))
                .addHeader("Accept", "application/json")
                .addHeader("X-Pay-App-Key", APP_KEY)
                .addHeader("X-Pay-Timestamp", ts)
                .addHeader("X-Pay-Nonce", nonce)
                .addHeader("X-Pay-Sign", sign)
                .build();

        try (Response resp = client.newCall(request).execute()) {
            int code = resp.code();
            String respBody = readBody(resp);
            System.out.println("HTTP 状态: " + code);
            System.out.println("响应体: " + respBody);

            boolean signFailed = code == 401 && respBody != null && respBody.contains("sign verify failed");
            if (signFailed) {
                throw new AssertionError("❌ 网关验签失败（sign verify failed）。请检查：\n"
                        + "  1) 私钥是否与网关 app_auth." + APP_KEY + " 公钥配对；\n"
                        + "  2) 4 个签名头是否齐备且未改动；\n"
                        + "  3) 时间戳是否在 ±300s 内；\n"
                        + "  4) body 是否逐字节一致（OkHttp 默认原样发送，不应被格式化）。\n"
                        + "本次响应：" + respBody);
            }
            System.out.println("✅ 验签通过（sign 关已过）；其余非 401 sign 类响应均属后端业务结果，与签名无关。");
        }
    }

    private static String readBody(Response resp) {
        try (ResponseBody b = resp.body()) {
            return b == null ? null : b.string();
        } catch (Exception e) {
            return "<read body error: " + e + ">";
        }
    }
}
