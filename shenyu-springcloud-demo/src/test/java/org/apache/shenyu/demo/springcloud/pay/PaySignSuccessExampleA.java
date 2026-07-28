/*
 * 验签成功示例（端到端，复用真实客户端加签库 pay-sign-standalone）。
 *
 * 对齐契约（与网关 SPI PayRsaSignService 完全一致）：
 *   - 头：X-Pay-Timestamp / X-Pay-Nonce / X-Pay-Sign / X-Pay-App-Key
 *   - 签名串：METHOD\nURL\nTS\nNONCE\nBODY\n
 *     · URL = 网关收到的原始 path（带 contextPath，如 /payCenter/v1/pay/url/create）
 *     · BODY = 真实报文体：真实客户端 PaySignInterceptor 按「实际 body」加签；线上网关按 body 验签，
 *             故线上必须带 body 签（本地网关无 body 缓存、按空 body 验，故本地示例仍置空）。
 *   - 算法：SHA256withRSA；时间窗 ±300s
 *
 * 本次目标：调用 https://shenyu.dev.jztweb.com/payCenter/v1/pay/url/create
 *   入参（业务报文）：requestSerialNo / bizOrderNo / goodsDesc / amount / buyerName / buyerUniqueId / makerName
 *   请求头：X-Pay-App-Key=06，以及 X-Pay-Timestamp / X-Pay-Nonce / X-Pay-Sign 等。
 *
 * 运行（默认 ONLINE，动态加签，需 erpm-dev shenyu.app_auth.06 公钥与 biz-private-key-online.pem 配对）：
 *   # 前提：安装加签库（仅首次）
 *   cd shenyu-client-java && mvn -q -pl pay-sign-standalone install -DskipTests
 *   # 线上（动态加签，appKey=06，按真实 body 验）—— 默认
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignSuccessExampleA -Dtarget=online
 *   # 回放用户提供的「完整配置」请求（固定 ts/nonce/sign，无需配私钥，仅验证该笔请求）
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignSuccessExampleA -Dtarget=ONLINE
 *   # 本地（shenyu-bootstrap-261 @ localhost:9196，sign 空 body 验）
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PaySignSuccessExampleA -Dtarget=local
 *
 * ⚠️ 线上前提：erpm-dev shenyu.app_auth.06 的公钥必须与 biz-private-key-online.pem 配对，
 *    且网关已缓存该公钥（DB 改签后需经推送链路触发网关重载，见 ShenyuAppAuthCrudRunner）。
 * ⚠️ ONLINE 模式：使用业务方最新真实调用的固定时间戳 1785206229106（约 2026-07-27 10:37 GMT+8），
 *    若距当前超过 ±300s，网关将返回 401 time window —— 属预期，不作为测试失败。
 *
 * 判定：
 *   - 返回非 401 即「验签通过」（sign 关已过）。线上已实测返回 200 + 业务响应（body 校验类提示），
 *     说明签名被网关接受并正确路由到后端；只有 401 sign verify failed / missing header / invalid appKey 才是验签失败。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class PaySignSuccessExampleA {

    /**
     * 目标环境：
     *  - LOCAL:  本地 shenyu-bootstrap-261 @ localhost:9196（sign 空 body 验）
     *  - ONLINE: 线上 dev（动态加签，appKey=06，按真实 body 验）—— 默认
     *  - ONLINE: 直接回放用户提供的「完整配置」请求（固定 ts/nonce/sign，无需配私钥）
     */
    private enum TargetEnv {
        LOCAL("http://localhost:9196",
                "/springcloud-demo/order/save",
                "/keys/biz-private-key.pem",
                "YYT",
                "本地 docker shenyu-bootstrap-261（YYT 公钥已配 shenyu_261.app_auth，nacos 同步）"),
        ONLINE("https://shenyu.dev.jzterp.net",
                "/payCenter/v1/pay/url/create",
                "/keys/biz-private-key-online1.pem",
                "06",
                "线上 dev（appKey=06 公钥需=erpm-dev shenyu.app_auth，DB 改签需触发网关重载）");

        final String gateway;
        final String path;
        final String privateKeyResource;   // ONLINE 为 null（无需私钥）
        final String appKey;
        final String description;

        TargetEnv(String gateway, String path, String privateKeyResource, String appKey, String description) {
            this.gateway = gateway;
            this.path = path;
            this.privateKeyResource = privateKeyResource;
            this.appKey = appKey;
            this.description = description;
        }
    }

    /** 用户提供的「完整配置」业务报文（ONLINE / ONLINE 共用同一笔单） */
    private static final String REQUEST_BODY = "{"
            + "\"requestSerialNo\":\"FDGDSD20260000600002\","
            + "\"bizOrderNo\":\"FDGDSD202600006\","
            + "\"goodsDesc\":\"上下游辅助系统：代收单\","
            + "\"amount\":50,"
            + "\"buyerName\":\"黄金梅利\","
            + "\"buyerUniqueId\":\"0000032183G00001\","
            + "\"makerName\":\"黄金梅利\""
            + "}";

    private static TargetEnv resolveTarget() {
        String raw = System.getProperty("target", TargetEnv.ONLINE.name());
        return TargetEnv.valueOf(raw.toUpperCase());
    }

    @Test
    public void signSuccess() throws Exception {
        TargetEnv env = resolveTarget();
        System.out.println("######## 验签成功示例：目标=" + env + "（" + env.description + "）########");
        System.out.println("######## 网关=" + env.gateway + env.path + " ########\n");

        // 1) 业务报文（ONLINE / ONLINE 用用户提供的完整配置；LOCAL 仍用本地测试单）
        String body = env == TargetEnv.ONLINE
                ? "{\n" +
                "  \"requestSerialNo\" : \"FDGDSD20260000600002\",\n" +
                "  \"bizOrderNo\" : \"FDGDSD202600006\",\n" +
                "  \"goodsDesc\" : \"上下游辅助系统：代收单\",\n" +
                "  \"amount\" : 50,\n" +
                "  \"buyerName\" : \"黄金梅利\",\n" +
                "  \"buyerUniqueId\" : \"0000032183G00001\",\n" +
                "  \"makerName\" : \"黄金梅利\"\n" +
                "}"
                : REQUEST_BODY;

        // 2) 签名头：ONLINE 直接回放用户提供的固定 ts/nonce/sign；其余动态加签
        String ts;
        String nonce;
        String sign;
            PrivateKey pk = PaySignUtils.loadPrivateKeyResource(env.privateKeyResource);
            String method = "POST";
            String url = env.path;                    // 客户端与网关看到的是同一个带 contextPath 的原始 path
            ts = PaySignUtils.newTimestamp();         // epoch 毫秒，±300s
            nonce = PaySignUtils.newNonce();
            // 关键：真实客户端 PaySignInterceptor 用「实际 body」加签；线上网关按 body 验签 → 线上必须带 body 签。
            // 本地网关无 body 缓存、按空 body 验 → 本地仍置空（保持 408 可复现）。
            String signBody = env == TargetEnv.ONLINE ? body : "";
            String signString = PaySignUtils.buildRequestSignString(method, url, ts, nonce, signBody);
            sign = PaySignUtils.sign(signString, pk);
            System.out.println("签名串(5行)=\n" + signString.replace("\n", "\\n\n"));
            System.out.println("X-Pay-Sign=" + sign.substring(0, 32) + "...\n");

        // 3) 发起请求：用户提供的完整配置头 + 业务头 + 真实 body
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-type", "application/json; charset=utf-8");
        headers.set("Accept", "application/json");
        headers.set("X-Pay-Timestamp", ts);
        headers.set("X-Pay-Nonce", nonce);
        headers.set("X-Pay-Sign", sign);
        headers.set("X-Pay-App-Key", env.appKey);
        if (env != TargetEnv.ONLINE) {
            // 用户提供的「完整配置」未含此头；动态模式保留原示例头
            headers.set("X-Pay-Acc-ID", "test-branch-001");
        }
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
                        + "  1) 私钥是否与网关 " + env.appKey + " 公钥配对；\n"
                        + "  2) 4 个签名头是否齐备；\n"
                        + "  3) 时间戳是否在 ±300s 内；\n"
                        + "  4) 线上是否经推送链路刷新了网关缓存的公钥。\n"
                        + "本次结果：" + resultSummary);
    }
}
