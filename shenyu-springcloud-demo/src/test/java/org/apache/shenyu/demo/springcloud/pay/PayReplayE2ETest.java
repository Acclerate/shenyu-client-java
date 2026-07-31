/*
 * PayReplayE2ETest —— 防重放（replay）端到端测试（本地 docker shenyu-bootstrap-261 @ localhost:9196）。
 *
 * 前置条件（自动探活，不满足时跳过测试）：
 *   1) shenyu-springcloud-demo 已启动（localhost:8471/actuator/health = UP）；
 *   2) 网关已启动（localhost:9196/actuator/health = UP 或无签名请求返回 401）；
 *   3) /springcloud-demo/order/save 已在 admin 的 sign 插件注册（Authentication），无签名头访问返回 401；
 *   4) 网关 app_auth 表存在 appKey=06，其公钥与 classpath:/keys/biz-private-key-online-06.pem 配对；
 *   5) bootstrap 容器环境变量 PAY_REPLAY_ENABLED=true 且 PAY_REPLAY_REDIS_URI 指向可达的 Redis（即防重放开启）。
 *
 * 环境不可达时使用 Assumptions.assumeTrue 跳过测试，不阻断构建（对齐 AGENTS.md §5）。
 *
 * 验证的三条用例（对齐 docs/pay-replay-防重放-设计开发方案.md §6 E2E）：
 *   [1] fresh（新 ts + 新 nonce，合法签名） → 过 sign 关（HTTP 200，或后端不可达时的 408/502 等「非 401」）；
 *   [2] replay（复用 [1] 的 ts + nonce + 签名串） → HTTP 401 + "replay request detected"；
 *   [3] fresh（再换全新 ts + nonce） → 再次过 sign 关。
 *
 * 判定口径（沿用 LocalSignVerifyTest 的成熟约定）：
 *   - 「过 sign 关」= 不是 401 sign verify failed / missing header / invalid appKey。
 *     后端路由超时(408)/连接失败(502) 等「非 401」都说明 sign 关已过，与防重放/验签逻辑无关。
 *   - 「防重放命中」= 唯一标志是 401 + 响应体含 "replay request detected"
 *     （该 message 由 PayRsaSignService.replayCheck 写入，是 replay 命中的确定信号）。
 *
 * 关键实现点（与网关 PayRsaSignService 的契约一致）：
 *   - 签名串第 5 行（BODY 段）= 真实请求体。当前运行时 jar 的 ShenYu sign 插件在 body 被 WebFlux
 *     缓存后才调用验签，传入的 requestBody 非空，故 BODY 段按真实报文加签（与 README 中
 *     「BODY 段恒为空」的旧描述相反——以网关 GW-Sign 日志的实际 bodyLen 为准）。
 *   - replay 用例复用 [1] 的同一签名串与 sign 值（ts/nonce/body 全相同 → signString 全相同 → sign 不变），
 *     这样 [2] 只可能因 replay 命中被拒，不会因签名不匹配被拒，结论无歧义。
 *
 * 运行：
 *   cd D:\privategit\github\shenyu-client-java
 *   mvn -pl shenyu-springcloud-demo test -Dtest=PayReplayE2ETest
 *
 * ⚠️ 依赖 pay-sign-standalone（已在默认 .m2：com.jzt.erpm.pay:pay-sign-standalone:1.0.0.0-SNAPSHOT）。
 *    若缺失：mvn -pl pay-sign-standalone install -DskipTests。
 * ⚠️ 注意：JDK 8+（需确保 pay-sign-standalone 的 JDK 8 版本可用）。
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防重放端到端测试。
 *
 * <p>对本地 9196 网关发起合法签名请求，验证网关侧 {@code PayReplayGuard} 能拦截同 (appKey,timestamp,nonce) 的重放。
 * 用例编排：fresh(过 sign) → replay(401 replay detected) → fresh(再过 sign)。
 */
public class PayReplayE2ETest {

    /** 网关地址 */
    private static final String GATEWAY = "http://localhost:9196";

    /** 网关 actuator health 端点（若不存在，则用无签名请求探活） */
    private static final String GATEWAY_HEALTH = "http://localhost:9196/actuator/health";

    /** springcloud-demo actuator health 端点 */
    private static final String SPRINGCLOUD_HEALTH = "http://localhost:8471/actuator/health";

    /** 测试路径 */
    private static final String PATH = "/springcloud-demo/order/save";

    /** 测试 appKey */
    private static final String APP_KEY = "06";
    /** 与 app_auth[06] 公钥配对的私钥（classpath，复用 demo test-resources） */
    private static final String PRIVATE_KEY_RESOURCE = "/keys/biz-private-key-online1.pem";

    /** 请求体（同时用作签名串 BODY 段与实际发送体，二者必须一致） */
    private static final String BODY = "{\"id\":\"replay-e2e-test\",\"name\":\"replay probe\"}";

    /**
     * 防重放三连测：新请求过签 → 重放被拒 → 新请求再过签。
     *
     * <p>环境不可达时跳过测试（Assumptions.assumeTrue），不阻断构建。
     *
     * @throws Exception IO / 加签 / HTTP 异常
     */
    @Test
    public void replay_attackedRequest_isRejected_freshRequest_passes() throws Exception {
        // ===== 环境探活：不满足则跳过测试（不阻断构建） =====
        assumeGatewayUp("网关不可达，跳过 E2E 测试（对齐 AGENTS.md §5「不阻断构建」）。"
                + "请确认：docker ps 有 shenyu-bootstrap-261 且健康。");
        assumeSpringcloudDemoUp("后端服务不可达，跳过 E2E 测试（对齐 AGENTS.md §5「不阻断构建」）。"
                + "请确认：docker ps 有 springcloud-demo 容器且 http://localhost:8471/actuator/health 返回 UP。");

        PrivateKey privateKey = PaySignUtils.loadPrivateKeyResource(PRIVATE_KEY_RESOURCE);

        // ===== [1] fresh：全新 ts + nonce，合法签名 → 期望过 sign 关 =====
        String ts1 = PaySignUtils.newTimestamp();
        String nonce1 = PaySignUtils.newNonce();
        // BODY 段用真实请求体（当前运行时 jar 的网关按真实 body 验签）
        String signString1 = PaySignUtils.buildRequestSignString("POST", PATH, ts1, nonce1, BODY);
        String sign1 = PaySignUtils.sign(signString1, privateKey);

        Resp r1 = post(PATH, APP_KEY, ts1, nonce1, sign1, BODY);
        System.out.println("[1] fresh   ts=" + ts1 + " nonce=" + nonce1 + " => HTTP " + r1.code + "  " + r1.body);

        boolean signPassed1 = !(r1.code == 401 && r1.body.contains("sign verify failed"));
        assertTrue(signPassed1,
                "[1] 新请求应过 sign 关（HTTP 非 401-sign-verify-failed 即可）。实际：HTTP " + r1.code + " " + r1.body
                        + "\n若 401 sign verify failed：检查私钥是否与 app_auth[06] 公钥配对、BODY 段是否与网关一致。");

        // ===== [2] replay：复用 [1] 的 ts/nonce/signString/sign → 期望 401 replay request detected =====
        // 关键：signString1 与 sign1 与 [1] 完全相同（ts/nonce/body 不变），
        //       故本请求若被 401 拒，只可能因 replay 命中，不会因签名不匹配被拒。
        Resp r2 = post(PATH, APP_KEY, ts1, nonce1, sign1, BODY);
        System.out.println("[2] replay  ts=" + ts1 + " nonce=" + nonce1 + " => HTTP " + r2.code + "  " + r2.body);

        boolean replayHit = (r2.code == 401 && r2.body.contains("replay request detected"));
        assertTrue(replayHit,
                "[2] 重放应被拦截（401 replay request detected）。实际：HTTP " + r2.code + " " + r2.body
                        + "\n若为 200/408 等「非 401」：说明 replay key 未命中——检查 PAY_REPLAY_ENABLED 是否 true、"
                        + "Redis 是否可达、PAY_REPLAY_REDIS_URI 是否正确。");

        // ===== [3] fresh：再换全新 ts + nonce → 期望再次过 sign 关（证明 replay 是 per-(appKey,ts,nonce) 的） =====
        String ts3 = PaySignUtils.newTimestamp();
        String nonce3 = PaySignUtils.newNonce();
        String signString3 = PaySignUtils.buildRequestSignString("POST", PATH, ts3, nonce3, BODY);
        String sign3 = PaySignUtils.sign(signString3, privateKey);

        Resp r3 = post(PATH, APP_KEY, ts3, nonce3, sign3, BODY);
        System.out.println("[3] fresh   ts=" + ts3 + " nonce=" + nonce3 + " => HTTP " + r3.code + "  " + r3.body);

        boolean signPassed3 = !(r3.code == 401 && r3.body.contains("sign verify failed"));
        assertTrue(signPassed3,
                "[3] 换新 nonce 后应再次过 sign 关。实际：HTTP " + r3.code + " " + r3.body);

        // ===== 汇总断言：整体没有出现任何意外的 401 验签失败 =====
        assertFalse(r1.code == 401 && r1.body.contains("sign verify failed")
                        || r3.code == 401 && r3.body.contains("sign verify failed"),
                "fresh 请求不应出现 401 sign verify failed");
    }

    /**
     * 发起一次 POST 请求（4 个签名头 + 真实 body），返回 HTTP 状态码与响应体。
     *
     * <p>401 时读 errorStream（HttpURLConnection 对 ≥400 不产生 InputStream）。
     *
     * @param path    网关原始路径（带 contextPath）
     * @param appKey  X-Pay-App-Key
     * @param ts      X-Pay-Timestamp（epoch 毫秒）
     * @param nonce   X-Pay-Nonce
     * @param sign    X-Pay-Sign（Base64）
     * @param body    真实请求体（同时也是签名串 BODY 段）
     * @return HTTP 状态码 + 响应体
     * @throws Exception IO 异常
     */
    private static Resp post(String path, String appKey, String ts, String nonce, String sign, String body)
            throws Exception {
        URL urlObj = new URL(GATEWAY + path);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Pay-App-Key", appKey);
        conn.setRequestProperty("X-Pay-Timestamp", ts);
        conn.setRequestProperty("X-Pay-Nonce", nonce);
        conn.setRequestProperty("X-Pay-Sign", sign);
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

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
        return new Resp(code, respBody);
    }

    /**
     * 探活网关服务。
     *
     * <p>优先尝试 GET /actuator/health，若失败则发一个无签名请求验证网关是否响应。
     *
     * @param skipMessage 跳过时的提示消息
     */
    private static void assumeGatewayUp(String skipMessage) {
        try {
            // 优先尝试 actuator/health
            URL url = new URL(GATEWAY_HEALTH);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            String body = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code == 200 && (body.contains("UP") || body.contains("status\":\"UP"))) {
                return; // actuator health 通过
            }
        } catch (Exception e) {
            // actuator 不存在或不可达，降级到无签名请求探活
        }

        // 降级：发一个无签名请求，期望返回 401（证明网关在处理请求）
        try {
            URL url = new URL(GATEWAY + PATH);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // 不设置任何 X-Pay-* 签名头
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));

            int code = conn.getResponseCode();
            String body = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            // 无签名请求应返回 401，或至少网关有响应（非连接拒绝）
            boolean gatewayResponding = (code == 401 && (body.contains("sign") || body.contains("missing header")))
                    || (code != -1 && !body.isEmpty());
            Assumptions.assumeTrue(gatewayResponding, skipMessage + "（网关无响应：" + code + " " + body + "）");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, skipMessage + "（连接异常：" + e.getMessage() + "）");
        }
    }

    /**
     * 探活 springcloud-demo 后端服务。
     *
     * <p>检查 actuator/health 是否返回 UP。
     *
     * @param skipMessage 跳过时的提示消息
     */
    private static void assumeSpringcloudDemoUp(String skipMessage) {
        try {
            URL url = new URL(SPRINGCLOUD_HEALTH);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            String body = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            boolean isUp = (code == 200 && (body.contains("UP") || body.contains("status\":\"UP")));
            Assumptions.assumeTrue(isUp, skipMessage + "（health 响应：" + code + " " + body + "）");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, skipMessage + "（连接异常：" + e.getMessage() + "）");
        }
    }

    /**
     * 读取 InputStream 全部内容。
     */
    private static String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 简单的 HTTP 响应持有者（状态码 + body）。 */
    private static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
