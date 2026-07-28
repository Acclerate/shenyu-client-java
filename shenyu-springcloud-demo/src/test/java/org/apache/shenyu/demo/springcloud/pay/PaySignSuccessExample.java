/*
 * 验签成功示例（端到端，复用真实客户端加签库 pay-sign-standalone）。
 *
 * 对齐契约（与网关 SPI PayRsaSignService 完全一致）：
 *   - 头：X-Pay-Timestamp / X-Pay-Nonce / X-Pay-Sign / X-Pay-App-Key
 *   - 签名串：METHOD\nURL\nTS\nNONCE\nBODY\n
 *     · URL = 网关收到的原始 path（带 contextPath，如 /payCenter/v1/pay/query）
 *     · BODY = 真实报文体：真实客户端 PaySignInterceptor 按「实际 body」加签；线上网关按 body 验签，
 *             故线上必须带 body 签（本地网关无 body 缓存、按空 body 验，故本地示例仍置空）。
 *   - 算法：SHA256withRSA；时间窗 ±300s
 *
 * 运行（默认 ONLINE，需 erpm-dev shenyu.app_auth.YYT 公钥与 biz-private-key-online.pem 配对）：
 *   # 前提：安装加签库（仅首次）
 *   cd shenyu-client-java && mvn -q -pl pay-sign-standalone install -DskipTests
 *   # 跑本例（线上）
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignSuccessExample -Dtarget=online
 *   # 本地（shenyu-bootstrap-261 @ localhost:9196，sign 空 body 验）
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignSuccessExample -Dtarget=local
 *
 * ⚠️ 线上前提：erpm-dev shenyu.app_auth.YYT 的公钥必须与 biz-private-key-online.pem 配对，
 *    且网关已缓存该公钥（DB 改签后需经推送链路触发网关重载，见 ShenyuAppAuthCrudRunner）。
 * ⚠️ 本沙箱直连内网域名需绕过代理；JUnit 在你本机跑时若线上可达则无需处理代理。
 *
 * 判定：
 *   - 返回非 401 即「验签通过」（sign 关已过）。线上已实测返回 200 + 业务响应（body 校验类提示），
 *     说明签名被网关接受并正确路由到后端；只有 401 sign verify failed / missing header / invalid appKey 才是验签失败。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class PaySignSuccessExample {

    private enum TargetEnv {
//        LOCAL("http://localhost:9196",
//                "/springcloud-demo/order/save",
//                "/keys/biz-private-key.pem",
//                "本地 docker shenyu-bootstrap-261（YYT 公钥已配 shenyu_261.app_auth，nacos 同步）"),
        ONLINE("https://shenyu.dev.jzterp.net",
                "/payCenter/v1/pay/query",
                "/keys/biz-private-key-online.pem",
                "线上 dev（YYT 公钥=erpm-dev shenyu.app_auth，DB 改签需触发网关重载）");

        final String gateway;
        final String path;
        final String privateKeyResource;
        final String description;

        TargetEnv(String gateway, String path, String privateKeyResource, String description) {
            this.gateway = gateway;
            this.path = path;
            this.privateKeyResource = privateKeyResource;
            this.description = description;
        }
    }

    private static final String APP_KEY = "YYT";

    private static TargetEnv resolveTarget() {
//        String raw = System.getProperty("target", TargetEnv.LOCAL.name());
        String raw = System.getProperty("target", TargetEnv.ONLINE.name());
        return TargetEnv.valueOf(raw.toUpperCase());
    }

    @Test
    public void signSuccess() throws Exception {
        TargetEnv env = resolveTarget();
        System.out.println("######## 验签成功示例：目标=" + env + "（" + env.description + "）########");
        System.out.println("######## 网关=" + env.gateway + env.path + " ########\n");

        // 1) 加载本环境配对私钥（PKCS#8 PEM）
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource(env.privateKeyResource);

        // 1.5) 业务报文（同时用于签名串 body 段与发送）
        String body = env == TargetEnv.ONLINE
                ? "{\"id\":\"TEST-LOCAL-001\",\"name\":\"pay sign verify success\"}"
                : "{\"requestSerialNo\":\"TESTREQ20260727001\"}";

        // 2) 构造 5 行签名串
        //    关键：真实客户端 PaySignInterceptor 用「实际 body」加签；线上网关按 body 验签 → 线上必须带 body 签。
        //    本地网关无 body 缓存、按空 body 验 → 本地仍置空（保持 408 可复现）。
        String method = "POST";
        String url = env.path;                    // 客户端与网关看到的是同一个带 contextPath 的原始 path
        String ts = PaySignUtils.newTimestamp();  // epoch 毫秒，±300s
        String nonce = PaySignUtils.newNonce();
        String signBody = env == TargetEnv.ONLINE ? body : "";
        String signString = PaySignUtils.buildRequestSignString(method, url, ts, nonce, signBody);
        String sign = PaySignUtils.sign(signString, pk);

        System.out.println("签名串(5行)=\n" + signString.replace("\n", "\\n\n"));
        System.out.println("X-Pay-Sign=" + sign.substring(0, 32) + "...\n");

        // 3) 发起请求：4 个签名头 + 业务头 + 真实 body（线上网关按真实 body 验签，故签名用同一 body）
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Pay-Timestamp", ts);
        headers.set("X-Pay-Nonce", nonce);
        headers.set("X-Pay-Sign", sign);
        headers.set("X-Pay-App-Key", APP_KEY);
        headers.set("X-Pay-Acc-ID", "test-branch-001");
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        String resultSummary;
        try {
            ResponseEntity<String> resp = rt.exchange(env.gateway + env.path, HttpMethod.POST, entity, String.class);
            int code = resp.getStatusCodeValue();
            System.out.println("HTTP 状态: " + code);
            System.out.println("响应体: " + resp.getBody());
            if (code == 200) {
                resultSummary = "✅ 验签通过且后端正常返回（完整成功）。";
            } else if (code == 401) {
                resultSummary = "❌ 被 401 拒绝：" + resp.getBody();
            } else {
                // 408 转发超时 / 后端 404 / 500 等：sign 关已过，只是后端问题
                resultSummary = "✅ 验签通过（sign 关已过，非 401）；后端返回 " + code + " 与验签无关。";
            }
        } catch (Exception e) {
            // RestTemplate 对 401 抛 HttpStatusCodeException，但本例不应是 401
            resultSummary = "❌ 调用异常：" + e;
        }

        System.out.println("\n==== 结论 ====");
        System.out.println(resultSummary);

        // 断言：任何 sign 类 401 都算失败（missing header / sign verify failed / invalid appKey）
        assertFalse(resultSummary.startsWith("❌"),
                "验签未通过，请检查：\n"
                        + "  1) 私钥是否与网关 YYT 公钥配对；\n"
                        + "  2) 4 个签名头是否齐备；\n"
                        + "  3) 时间戳是否在 ±300s 内；\n"
                        + "  4) 线上是否经推送链路刷新了网关缓存的公钥。\n"
                        + "本次结果：" + resultSummary);
    }
}
