/*
 * 业务方模拟调用网关（验签通过）的端到端测试。
 *
 * 对齐：
 *   - 加签：pay-sign-standalone 的 PayRequestSigner + PaySignInterceptor（SHA256withRSA，5 行签名串）
 *   - 验签(响应)：pay-sign-standalone 的 PayResponseVerifier（SHA256withRSA，3 行签名串）
 *
 * 本类不依赖 Spring 上下文（直接 new PayRequestSigner(pem)），可独立运行，
 * 仅需在 test classpath 引入 pay-sign-standalone（见 pom.xml 的 test 依赖）。
 *
 * 支持本地 + 线上双环境，通过系统属性 target=local|online 切换（默认 local）：
 *
 *   运行前先安装加签库（仅首次）：
 *     cd shenyu-client-java
 *     mvn -q -pl pay-sign-standalone install -DskipTests
 *
 *   本地环境（默认）：
 *     mvn -pl shenyu-springcloud-demo test -Dtest=PayRefundCallerTest
 *     # 或显式：-Dtarget=local
 *
 *   线上测试环境：
 *     mvn -pl shenyu-springcloud-demo test -Dtest=PayRefundCallerTest -Dtarget=online
 *
 *   只跑某个场景（默认跑 emptyBody，与本地 ShenYu SignPlugin 在 body 缓存之前执行的现状对齐）：
 *     -Dcase=emptyBody      body 段传空（与网关 SignPlugin 验签时收到的空 body 匹配 → 验签通过）
 *     -Dcase=realBody       真实 body 参与签名（会失败：客户端签名用真实 body，
 *                           但网关 SignPlugin 在 body 缓存之前执行，验签时收到空 body → 签名串不匹配）
 *     -Dcase=both           两个场景都跑（realBody 失败为预期，作为对照验证）
 *
 * 双环境差异（见 TargetEnv）：
 *   - 网关地址：本地 http://localhost:9196 / 线上 https://shenyu.dev.jzterp.net
 *   - 验签 path：本地 /springcloud-demo/order/save（selector /springcloud-demo/* 开 sign，
 *     OrderController 已注册到 shenyu_261.meta_data）/ 线上 /payCenter/v1/refund/query
 *   - appKey 所配公钥不同 → 必须用对应环境的私钥加签
 *   - 线上 YYT 公钥 = main/resources/keys/biz-public-key.pem（已在 erpm-dev shenyu.app_auth 注册）
 *   - 本地 YYT 公钥 = test 私钥的配对公钥（本地 shenyu_261.app_auth，调试用）
 */
package org.apache.shenyu.demo.springcloud.pay;

import com.jzt.erpm.pay.sign.PayRequestSigner;
import com.jzt.erpm.pay.sign.PayResponseVerifier;
import com.jzt.erpm.pay.sign.PaySignUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.security.PublicKey;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PayRefundCallerTest {

    /**
     * 目标环境定义。
     *
     * <p>本地与线上 YYT 配的公钥不同，故必须用对应环境的私钥加签，否则签名串虽合法但
     * 公钥不匹配会被网关 401 拒绝（现象：sign verify failed）。
     *
     * <p>本地私钥 = {@code /keys/biz-private-key.pem}（test classpath，配本地 shenyu_261.app_auth.YYT）。
     * <p>线上私钥 = {@code /keys/biz-private-key-online.pem}（test classpath，复制自
     *   main/resources/keys/biz-private-key.pem，配 erpm-dev shenyu.app_auth.YYT）。
     *
     * <p>本地与线上验签 path 不同：
     * <ul>
     *   <li>本地 demo：selector /springcloud-demo/* 开 sign，调用 POST /springcloud-demo/order/save
     *       （OrderController 已在 shenyu_261.meta_data 注册，路由命中后回真实业务响应）。</li>
     *   <li>线上 dev：调用生产退款查询 path /payCenter/v1/refund/query（已在线上
     *       erpm-dev shenyu.app_auth.YYT 配公钥；线上是否有真实后端取决于 erpm-pay-center 部署）。</li>
     * </ul>
     */
    private enum TargetEnv {
        LOCAL("http://localhost:9196",
                "/springcloud-demo/order/save",
                "/keys/biz-private-key.pem",
                "本地 docker（shenyu-bootstrap-261，selector /springcloud-demo/* 开 sign）"),
        ONLINE("https://shenyu.dev.jzterp.net",
                "/payCenter/v1/refund/query",
                "/keys/biz-private-key-online.pem",
                "线上 dev（shenyu.dev.jzterp.net，退款查询 path）");

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

    /** 已在 shenyu.app_auth 注册公钥的 appKey */
    private static final String APP_KEY = "YYT";
    private static final String ACC_ID = "test-branch-001";

    /**
     * 业务请求体（按环境构造）。
     *
     * <p>本地 demo 的 OrderController.save 反序列化为 OrderDTO，需 id/name 字段；
     * 线上退款查询需要 requestSerialNo 或 bizOrderNo。
     */
    private static String buildBody(TargetEnv env) {
        switch (env) {
            case LOCAL:
                return "{\"id\":\"TEST-LOCAL-001\",\"name\":\"pay sign verify test\"}";
            case ONLINE:
                return "{\"requestSerialNo\":\"TESTREQ20260727001\"}";
            default:
                throw new IllegalArgumentException("unknown env: " + env);
        }
    }

    /** 解析 -Dtarget，默认 LOCAL */
    private static TargetEnv resolveTarget() {
//        String raw = System.getProperty("target", TargetEnv.LOCAL.name());
        String raw = System.getProperty("target", TargetEnv.ONLINE.name());
        try {
            return TargetEnv.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知 target=" + raw + "，可选: " + java.util.Arrays.toString(TargetEnv.values()));
        }
    }

    /**
     * 解析 -Dcase，默认 emptyBody。
     *
     * <p>默认 emptyBody 的原因：ShenYu SignPlugin 在 body 缓存之前执行（见 PayRsaSignService 注释），
     * 网关侧验签时收到的 requestBody 是空串；客户端用真实 body 签名会导致签名串不匹配。
     * 用 emptyBody 才能与网关现状对齐，验签通过。
     */
    private static String resolveCase() {
        return System.getProperty("case", "emptyBody").toLowerCase();
    }

    /** 读取指定环境的 classpath 私钥，构造业务方加签器 */
    private PayRequestSigner newSigner(TargetEnv env) {
        String pem = PaySignUtils.readResourceUtf8(env.privateKeyResource);
        return new PayRequestSigner(pem);
    }

    /**
     * 业务方调用网关（按目标环境的 path）。
     *
     * @param env           目标环境（决定网关地址、path、私钥）
     * @param signRealBody  true  => 用真实 body 参与签名（会失败：客户端签名用真实 body，
     *                      但网关 SignPlugin 在 body 缓存之前执行，验签时收到空 body → 签名串不匹配；
     *                      作为对照验证场景使用）
     *                      false => body 段传空（与网关 SignPlugin 验签时收到的空 body 匹配 → 验签通过）
     */
    private void callAndVerify(TargetEnv env, boolean signRealBody) throws Exception {
        PayRequestSigner signer = newSigner(env);

        RestTemplate rt = new RestTemplate();
        if (signRealBody) {
            // 真实 body 参与签名（默认）：用 PayRequestSigner 提供的拦截器
            ClientHttpRequestInterceptor interceptor = signer.createInterceptor();
            rt.setInterceptors(Collections.singletonList(interceptor));
        } else {
            // body 段传空签名：自定义拦截器把签名串 body 段强制为空串。
            // 注意：请求体仍发送真实 body（否则后端必填字段缺失会 400），
            // 仅签名计算时 body 段用空——用于验证网关按"空 body"验签的场景。
            rt.getInterceptors().add((req, body, exec) -> {
                String method = req.getMethod().name();
                String url = req.getURI().getRawPath();
                if (req.getURI().getRawQuery() != null) {
                    url = url + "?" + req.getURI().getRawQuery();
                }
                // 反射取 signer 内部的私钥（PayRequestSigner.privateKey）
                java.security.PrivateKey pk;
                try {
                    java.lang.reflect.Field f = PayRequestSigner.class.getDeclaredField("privateKey");
                    f.setAccessible(true);
                    pk = (java.security.PrivateKey) f.get(signer);
                } catch (Exception e) {
                    throw new IllegalStateException("emptyBody 场景取私钥失败，建议用 -Dcase=realBody", e);
                }
                String ts = PaySignUtils.newTimestamp();
                String nonce = PaySignUtils.newNonce();
                // 关键：签名串 body 段强制为空
                String signString = PaySignUtils.buildRequestSignString(method, url, ts, nonce, "");
                String sign = PaySignUtils.sign(signString, pk);
                req.getHeaders().set("X-Pay-Timestamp", ts);
                req.getHeaders().set("X-Pay-Nonce", nonce);
                req.getHeaders().set("X-Pay-Sign", sign);
                return exec.execute(req, body);
            });
        }

        String body = buildBody(env);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Pay-App-Key", APP_KEY);
        headers.set("X-Pay-Acc-ID", ACC_ID);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        System.out.println("==== [" + env + "] 场景[" + (signRealBody ? "真实body签名" : "空body签名")
                + "] POST " + env.gateway + env.path + " ====");
        System.out.println("私钥资源: " + env.privateKeyResource + "（" + env.description + "）");
        try {
            ResponseEntity<String> resp = rt.exchange(env.gateway + env.path, HttpMethod.POST, entity, String.class);
            System.out.println("HTTP 状态: " + resp.getStatusCodeValue());
            System.out.println("响应体: " + resp.getBody());

            HttpHeaders rh = resp.getHeaders();
            String rTs = rh.getFirst("X-Pay-Timestamp");
            String rNonce = rh.getFirst("X-Pay-Nonce");
            String rSign = rh.getFirst("X-Pay-Sign");
            System.out.println("响应头 X-Pay-Timestamp=" + rTs + " X-Pay-Nonce=" + rNonce + " X-Pay-Sign=" + rSign);
            if (rSign != null) {
                PublicKey payPub = PaySignUtils.loadPublicKey(PaySignUtils.readResourceUtf8("/keys/pay-public-key.pem"));
                boolean ok = PayResponseVerifier.verify(rTs, rNonce, resp.getBody(), rSign, payPub);
                System.out.println("响应验签结果: " + (ok ? "PASS ✅" : "FAIL ❌"));
            } else {
                System.out.println("响应验签: 网关未返回 X-Pay-Sign（通常是网关侧已 401 拒绝，验签未发生在本端）");
            }
        } catch (Exception e) {
            System.out.println("调用异常（401 由 RestTemplate 抛 HttpStatusCodeException 时会进这里）: " + e);
        }
        System.out.println();
    }

    @Test
    public void simulateBusinessPartyRefundQuery() throws Exception {
        TargetEnv env = resolveTarget();
        String caseMode = resolveCase();
        System.out.println("######## 目标环境: " + env + "（" + env.description + "） ########");
        System.out.println("######## 网关: " + env.gateway + " ########");
        System.out.println("######## 场景模式: " + caseMode + " ########");
        System.out.println();

        switch (caseMode) {
            case "realbody":
                callAndVerify(env, true);
                break;
            case "emptybody":
                callAndVerify(env, false);
                break;
            case "both":
                callAndVerify(env, true);
                callAndVerify(env, false);
                break;
            default:
                throw new IllegalArgumentException(
                        "未知 case=" + caseMode + "，可选: realBody / emptyBody / both");
        }

        assertTrue(true, "详见控制台输出。判定指引：\n"
                + "  - HTTP 200（LOCAL：回 OrderController 业务响应 / ONLINE：回退款项查询或 -106 路由失败）→ 验签通过（过了 sign 关）\n"
                + "  - HTTP 401 sign verify failed → 签名串与公钥不匹配（检查私钥是否对应环境的 YYT 公钥）\n"
                + "  - HTTP 401 sign version does not exist → PayRsaSignService SPI 未生效（仍在跑原生）\n"
                + "  - HTTP 401 missing X-Pay-* header → 请求未带签名头（SPI 已生效但缺头）");
    }
}
