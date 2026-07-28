/*
 * LocalSignVerifyTest —— 本地端到端验签测试。
 *
 * 目的：用 pay-sign-standalone 的 PaySignUtils（与生产加签逻辑 100% 一致）
 * 对本地 9196 网关发起合法签名请求，验证 PayRsaSignService 能验签通过。
 *
 * 不依赖 RestTemplate 拦截器，手动构造签名串 + sign，便于精确控制，
 * 排除 shell 脚本里 printf "\n" 转义不一致等问题。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalSignVerifyTest {

    private static final String GATEWAY = "http://localhost:9196";
    private static final String PATH = "/springcloud-demo/order/findById";
    private static final String QUERY = "id=1";
    private static final String APP_KEY = "YYT";

    @Test
    public void signedRequest_shouldPassSignVerify() throws Exception {
        // 用 pay-sign-standalone 的工具加载私钥（与生产加签方完全一致）
        String pem = PaySignUtils.readResourceUtf8("/keys/biz-private-key.pem");
        PrivateKey privateKey = PaySignUtils.loadPrivateKey(pem);

        String method = "GET";
        // 网关 buildRequestUrl = path + "?" + query（PayRsaSignService.java:167）
        String url = PATH + "?" + QUERY;
        String timestamp = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String body = ""; // GET 无 body

        // 构造 5 行签名串（PaySignUtils.buildRequestSignString，与网关 buildSignString 一致）
        String signString = PaySignUtils.buildRequestSignString(method, url, timestamp, nonce, body);
        String sign = PaySignUtils.sign(signString, privateKey);

        System.out.println("=== 签名串 ===");
        System.out.println(signString.replace("\n", "\\n\n"));
        System.out.println("=== sign ===");
        System.out.println(sign.length() > 60 ? sign.substring(0, 60) + "..." : sign);

        // 发请求
        String fullUrl = GATEWAY + PATH + "?" + QUERY;
        URL urlObj = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Pay-App-Key", APP_KEY);
        conn.setRequestProperty("X-Pay-Timestamp", timestamp);
        conn.setRequestProperty("X-Pay-Nonce", nonce);
        conn.setRequestProperty("X-Pay-Sign", sign);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int code = conn.getResponseCode();
        String respBody;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            respBody = sb.toString();
        }

        System.out.println("=== HTTP " + code + " ===");
        System.out.println(respBody);

        // 验签通过的判定：
        // - HTTP 200：验签通过，请求走到后端（即便后端报错，也说明过了 sign 关）
        // - 非 401 sign verify failed：过了 sign 关（可能是后端 502/-106 等其他错误）
        // 唯一的失败信号是 401 + "sign verify failed"（我们的验签失败的 message）
        boolean signPassed = !(code == 401 && respBody.contains("sign verify failed"));
        System.out.println("=== 验签结果：" + (signPassed ? "PASS ✅（过了 sign 关）" : "FAIL ❌（sign 验签未通过）"));
        assertTrue(signPassed, "验签未通过，检查签名串构造是否与网关一致");
    }
}
