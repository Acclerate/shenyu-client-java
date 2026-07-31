/*
 * 线上防重放（replay）端到端测试 —— 基于 GatewaySignCallExampleTest 的 OkHttp 直签路径。
 *
 * 目的：单独验证「线上网关 shenyu.dev.jztweb.com」的防重放（replay）功能是否生效。
 *   不依赖本地 docker、不依赖 Spring 容器、不依赖 shenyu-springcloud-demo，
 *   纯 OkHttp + PaySignUtils 直签，与生产加签字节完全一致。
 *
 * 用例编排（三连）：
 *   A fresh   全新 ts+nonce+sign，合法签名 → 期望「过 sign 关」（非 401 sign verify failed）
 *   B replay  复用 A 的 ts+nonce+sign 原样重发 → 期望 401 + "replay request detected"
 *   C fresh   再换全新 ts+nonce+sign → 期望再次「过 sign 关」（证明 replay 是 per-(appKey,ts,nonce)）
 *
 * 判定口径（沿用 GatewaySignCallExampleTest / LocalSignVerifyTest 的成熟约定）：
 *   - 「过 sign 关」= HTTP 非 401，或 401 但响应体不含 "sign verify failed"
 *     （200/408/502/后端业务码 等都说明 sign 关已过，与防重放/验签逻辑无关）。
 *   - 「防重放命中」= 唯一标志 401 + 响应体含 "replay request detected"。
 *
 * 关键实现点：
 *   - replay 用例 B 复用 A 的 ts+nonce+sign（三者全相同 → signString 全相同 → sign 不变），
 *     故 B 若被 401 拒，只可能因 replay 命中，不会因签名不匹配被拒，结论无歧义。
 *   - OkHttp body 逐字节原样发送，与签名串 BODY 段逐字节一致（任何格式化都会破坏签名）。
 *
 * ⚠️ 前提（不满足时测试以断言失败 + 明确提示退出）：
 *   1) 本机能直连内网域名 shenyu.dev.jztweb.com（与 GatewaySignViaInterceptorTest 同网络前提）；
 *   2) 线上网关 PAY_REPLAY_ENABLED=true 且 Redis 可达（防重放开关在线上已开启）；
 *   3) 线上 app_auth 表存在 appKey=06，其公钥与 classpath:/keys/biz-private-key-online-06.pem 配对。
 *   若 B 用例不是 401 replay，而是 200/408，说明线上防重放未开启或 Redis 未生效——
 *   这不是本测试代码问题，需检查线上网关 PAY_REPLAY_* 配置。
 *
 * 运行：
 *   mvn -f pay-sign-standalone/pom.xml test -Dtest=GatewayReplayProtectionOnlineTest
 */
package com.jzt.erpm.pay.sign;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线上防重放端到端测试。
 *
 * <p>对线上网关 {@code https://shenyu.dev.jztweb.com} 发起合法签名请求，
 * 验证网关侧 {@code PayReplayGuard} 能拦截同 (appKey,timestamp,nonce) 的重放。
 * 用例：fresh(过 sign) → replay(401 replay detected) → fresh(再过 sign)。
 *
 * <p>与 {@link GatewaySignViaInterceptorTest}（生产 Spring 拦截器路径）的区别：
 * 本测试用 OkHttp 直签，刻意复用上一次的 ts/nonce/sign 来构造「重放」，
 * 专门验证防重放维度；前者只验证单次验签闭环。
 */
public class GatewayReplayProtectionOnlineTest {

    /** 线上网关（与 GatewaySignViaInterceptorTest / GatewaySignCallExampleTest 一致） */
    private static final String GATEWAY = "https://shenyu.dev.jztweb.com";
    private static final String PATH = "/payCenter/v1/pay/url/create";
    private static final String METHOD = "POST";
    private static final String APP_KEY = "06";
    /** appKey=06 对应的生产私钥（classpath:/keys/biz-private-key-online-06.pem） */
    private static final String PRIVATE_KEY_RESOURCE = "/keys/biz-private-key-online-06.pem";

    /** 与签名逐字节绑定的请求体（OkHttp 原样发送，任何格式化都会破坏签名） */
    private static final String BODY =
            "{\n"
            + "  \"requestSerialNo\" : \"REPLAY-E2E-0001\",\n"
            + "  \"bizOrderNo\" : \"REPLAY-E2E-0001\",\n"
            + "  \"goodsDesc\" : \"防重放端到端测试\",\n"
            + "  \"amount\" : 1,\n"
            + "  \"buyerName\" : \"replay-test\",\n"
            + "  \"buyerUniqueId\" : \"REPLAY-E2E-BUYER-0001\",\n"
            + "  \"makerName\" : \"replay-test\"\n"
            + "}";

    /** 单个签名请求的结果（4 个签名头 + 本次签名串/sign，便于重放复用与展示）。 */
    private static final class SignedReq {
        final String ts;
        final String nonce;
        final String sign;
        final String signString;
        SignedReq(String ts, String nonce, String sign, String signString) {
            this.ts = ts; this.nonce = nonce; this.sign = sign; this.signString = signString;
        }
    }

    /** 一次 HTTP 调用的结果。 */
    private static final class HttpResp {
        final int code;
        final String body;
        HttpResp(int code, String body) { this.code = code; this.body = body; }
    }

    @Test
    public void online_replay_blocked_and_fresh_passes() throws Exception {
        PrivateKey pk = PaySignUtils.loadPrivateKeyResource(PRIVATE_KEY_RESOURCE);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        System.out.println("============================================================");
        System.out.println("  线上防重放端到端测试（GatewayReplayProtectionOnlineTest）");
        System.out.println("============================================================");
        System.out.println("网关: " + GATEWAY + PATH);
        System.out.println("appKey: " + APP_KEY);
        System.out.println("Body:\n" + BODY + "\n");

        // ===== A: fresh 全新签名 → 期望过 sign 关 =====
        SignedReq a = freshSign(pk);
        HttpResp rA = send(client, a);
        printCase("[A fresh] 全新 ts+nonce+sign，合法签名", a, rA);
        boolean signPassedA = signGatePassed(rA);
        assertTrue(signPassedA,
                "[A] 新请求应过 sign 关。实际：" + rA.code + " " + rA.body
                        + "\n若 401 sign verify failed：检查私钥是否与线上 app_auth[" + APP_KEY
                        + "] 公钥配对、body 是否逐字节一致。");

        // ===== B: replay 复用 A 的 ts+nonce+sign 原样重发 → 期望 401 replay request detected =====
        HttpResp rB = send(client, a);
        printCase("[B replay] 复用 A 的 ts+nonce+sign（重放）", a, rB);
        boolean replayHit = (rB.code == 401 && rB.body != null && rB.body.contains("replay request detected"));
        assertTrue(replayHit,
                "[B] 重放应被拦截（401 replay request detected）。实际：" + rB.code + " " + rB.body
                        + "\n若为 200/408 等「非 401」：说明线上防重放未生效——"
                        + "检查线上网关 PAY_REPLAY_ENABLED 是否 true、Redis 是否可达、PAY_REPLAY_REDIS_URI 是否正确。"
                        + "\n这不是本测试代码问题，是线上配置问题。");
        System.out.println("    replay key 契约 = replay:" + APP_KEY + ":" + a.ts + ":" + a.nonce);
        System.out.println("    （A 首次 SET NX 成功标记 → B 发现 key 已存在 → 401）\n");

        // ===== C: fresh 再换全新签名 → 期望再次过 sign 关（证明 replay 是 per-(appKey,ts,nonce)） =====
        SignedReq c = freshSign(pk);
        HttpResp rC = send(client, c);
        printCase("[C fresh] 换全新 ts+nonce+sign", c, rC);
        boolean signPassedC = signGatePassed(rC);
        assertTrue(signPassedC,
                "[C] 换新 nonce 后应再次过 sign 关。实际：" + rC.code + " " + rC.body);

        // ===== 汇总 =====
        assertFalse((rA.code == 401 && rA.body != null && rA.body.contains("sign verify failed"))
                        || (rC.code == 401 && rC.body != null && rC.body.contains("sign verify failed")),
                "fresh 请求不应出现 401 sign verify failed");

        System.out.println("============================================================");
        System.out.println("  汇总: A 过sign关=" + signPassedA
                + "  B 防重放命中=" + replayHit
                + "  C 过sign关=" + signPassedC);
        System.out.println("  ✅✅✅ 线上防重放功能验证通过 ✅✅✅");
        System.out.println("============================================================");
    }

    // ============================ 核心动作 ============================

    /** 用生产私钥对固定 body 做一次全新签名（新 ts + 新 nonce）。 */
    private static SignedReq freshSign(PrivateKey pk) {
        String ts = PaySignUtils.newTimestamp();
        String nonce = PaySignUtils.newNonce();
        String signString = PaySignUtils.buildRequestSignString(METHOD, PATH, ts, nonce, BODY);
        String sign = PaySignUtils.sign(signString, pk);
        return new SignedReq(ts, nonce, sign, signString);
    }

    /** 用指定的签名结果发送一次 POST（body 逐字节原样发送）。 */
    private static HttpResp send(OkHttpClient client, SignedReq s) throws Exception {
        Request request = new Request.Builder()
                .url(GATEWAY + PATH)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), BODY))
                .addHeader("Accept", "application/json")
                .addHeader("X-Pay-App-Key", APP_KEY)
                .addHeader("X-Pay-Timestamp", s.ts)
                .addHeader("X-Pay-Nonce", s.nonce)
                .addHeader("X-Pay-Sign", s.sign)
                .build();
        try (Response resp = client.newCall(request).execute()) {
            return new HttpResp(resp.code(), readBody(resp));
        }
    }

    /** 打印单个用例的完整过程：签名串 5 行 + sign + HTTP 响应。 */
    private static void printCase(String title, SignedReq s, HttpResp r) {
        System.out.println("------------------------------------------------------------");
        System.out.println(title);
        System.out.println("------------------------------------------------------------");
        System.out.println("签名串 5 行 (each line ends with \\n):");
        String[] lines = s.signString.split("\n", -1);
        String[] tags = {"METHOD", "URL", "TIMESTAMP", "NONCE", "BODY"};
        for (int i = 0; i < 5; i++) {
            String v = lines[i].isEmpty() ? "(empty)" : lines[i];
            System.out.println("  第" + (i + 1) + "行 [" + tags[i] + "] = " + v);
        }
        System.out.println("X-Pay-Sign = " + s.sign);
        System.out.println("HTTP " + r.code + "  body=" + r.body);
    }

    // ============================ 判定与工具 ============================

    /** 过 sign 关 = 非 401，或 401 但不含 sign verify failed（后端 408/502/业务码等非签类也算过）。 */
    private static boolean signGatePassed(HttpResp r) {
        return !(r.code == 401 && r.body != null && r.body.contains("sign verify failed"));
    }

    private static String readBody(Response resp) {
        try (ResponseBody b = resp.body()) {
            return b == null ? null : b.string();
        } catch (Exception e) {
            return "<read body error: " + e + ">";
        }
    }
}
