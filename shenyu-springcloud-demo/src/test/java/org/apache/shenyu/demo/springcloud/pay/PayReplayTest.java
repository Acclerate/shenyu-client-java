/*
 * Replay (anti-replay) quick test -- single file, zero dependency.
 *
 * Run (JDK 11+, single-file source mode; NO compile, NO Maven, NO 3rd-party lib):
 *   java src/test/java/org/apache/shenyu/demo/springcloud/pay/PayReplayTest.java
 * 或在 IDEA 里直接点本类的 main 方法运行（已配默认参数，无需 -D）。
 *
 * Optional overrides (all have defaults = the verified working local env):
 *   java -Dgateway=http://localhost:9196 \
 *        -Dpath=/springcloud-demo/order/save \
 *        -DappKey=06 \
 *        -DkeyPath=D:/.../biz-private-key-online-06.pem \
 *        -Dbody='{"id":"x","name":"y"}' \
 *        src/test/java/org/apache/shenyu/demo/springcloud/pay/PayReplayTest.java
 *
 * Three cases (aligned with docs/pay-replay design doc section 6 E2E):
 *   A fresh   new ts+nonce, valid signature      -> expect "sign gate passed" (HTTP 200, or 408/502 if backend unreachable)
 *   B replay  reuse A's ts+nonce+signature        -> expect 401 + "replay request detected"
 *   C fresh   brand new ts+nonce again            -> expect "sign gate passed" again (replay is per-(appKey,ts,nonce))
 *
 * Verdict (same convention as LocalSignVerifyTest):
 *   - "sign gate passed" = NOT 401 sign verify failed / missing header / invalid appKey.
 *     Backend 408/502 etc. (non-401) still mean sign gate passed; unrelated to verify/replay logic.
 *   - "replay hit" = the only marker: 401 + response body contains "replay request detected".
 *
 * Signature contract (matches gateway PayRsaSignService):
 *   signString = METHOD\nURL\nTS\nNONCE\nBODY\n (5 lines, each ending with \n)
 *   line 5 BODY = the REAL request body (current runtime jar verifies with real body, verified)
 *   algo = SHA256withRSA; timestamp = epoch ms, tolerance +-300s
 *
 * Output is pure ASCII on purpose: avoids any console-encoding garble on Windows.
 */
package org.apache.shenyu.demo.springcloud.pay;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class PayReplayTest {

    // ===== Configurable (override with -D) =====
    static final String GATEWAY  = System.getProperty("gateway", "http://localhost:9196");
    static final String PATH     = System.getProperty("path", "/springcloud-demo/order/save");
    static final String APP_KEY  = System.getProperty("appKey", "06");
    static final String KEY_PATH = System.getProperty("keyPath",
            "D:/privategit/github/shenyu-client-java/shenyu-springcloud-demo/src/main/resources/keys/biz-private-key-online-06.pem");
    static final String BODY     = System.getProperty("body", "{\"id\":\"replay-quick-test\",\"name\":\"quick\"}");

    static final String HR = repeat('-', 78);

    public static void main(String[] args) throws Exception {
        PrivateKey priv = loadPrivateKey(KEY_PATH);

        System.out.println("==============================================================================");
        System.out.println(" gateway = " + GATEWAY + PATH);
        System.out.println(" appKey  = " + APP_KEY + "    BODY = " + BODY);
        System.out.println(" privKey = " + KEY_PATH);
        System.out.println("==============================================================================\n");

        // ===== A: sign verify success =====
        String tsA = newTs(), nonceA = newNonce();
        Resp rA = post(tsA, nonceA, BODY, priv);
        printCase("[A sign-verify-success] fresh: new ts + new nonce, valid signature", rA);
        boolean passA = signGatePassed(rA);
        System.out.println(">>> Result A: " + (passA ? "[PASS] sign verify passed (sign gate)"
                : "[FAIL] sign verify failed") + tip(rA) + "\n");

        // ===== B: replay hit (reuse A's ts/nonce/body -> identical signString/sign) =====
        Resp rB = post(tsA, nonceA, BODY, priv);
        printCase("[B replay-blocked] replay: reuse A's ts + nonce (signString/sign identical)", rB);
        boolean hitB = (rB.code == 401 && rB.body.contains("replay request detected"));
        System.out.println(">>> Result B: " + (hitB ? "[PASS] replay hit, blocked" : "[WARN] replay NOT hit"));
        System.out.println("    replay key contract = replay:" + APP_KEY + ":" + tsA + ":" + nonceA);
        System.out.println("    (A first SET NX success -> B finds key exists -> 401)\n");

        // ===== C: control =====
        String tsC = newTs(), nonceC = newNonce();
        Resp rC = post(tsC, nonceC, BODY, priv);
        printCase("[C control] fresh: brand new ts + nonce", rC);
        boolean passC = signGatePassed(rC);
        System.out.println(">>> Result C: " + (passC ? "[PASS] sign verify passed again" : "[FAIL] sign verify failed") + "\n");

        // ===== Summary =====
        System.out.println("================================================================================");
        System.out.printf(" SUMMARY: A sign-verify-success=%b   B replay-hit=%b   C sign-verify-success=%b%n",
                passA, hitB, passC);
        if (passA && hitB && passC) {
            System.out.println(" ====> ALL PASS : anti-replay function verified OK <====");
        } else {
            System.out.println(" ====> SOME UNEXPECTED : check details above and gateway GW-Sign/GW-Replay logs <====");
        }
        System.out.println("================================================================================");
    }

    // ---- core action ----

    /** Send one signed POST; return HTTP code + body + the signString/sign used (for display). */
    static Resp post(String ts, String nonce, String body, PrivateKey priv) throws Exception {
        String signString = buildSignString("POST", PATH, ts, nonce, body); // BODY line = real body
        String sign = sign(signString, priv);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        URL urlObj = new URL(GATEWAY + PATH);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Pay-App-Key", APP_KEY);
        conn.setRequestProperty("X-Pay-Timestamp", ts);
        conn.setRequestProperty("X-Pay-Nonce", nonce);
        conn.setRequestProperty("X-Pay-Sign", sign);
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.getOutputStream().write(payload);

        int code = conn.getResponseCode();
        String respBody = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
        return new Resp(code, respBody, signString, sign);
    }

    /** Print one case's full process: signString 5 lines + sign + response. */
    static void printCase(String title, Resp r) {
        System.out.println(HR);
        System.out.println(title);
        System.out.println(HR);
        System.out.println("[signString 5 lines] (each line ends with \\n)");
        String[] lines = r.signString.split("\n", -1);
        String[] tags = {"METHOD", "URL", "TIMESTAMP", "NONCE", "BODY"};
        for (int i = 0; i < 5; i++) {
            String v = lines[i].isEmpty() ? "(empty)" : lines[i];
            System.out.println("  line " + (i + 1) + " [" + tags[i] + "] = " + v);
        }
        System.out.println("[X-Pay-Sign] (SHA256withRSA, Base64) =");
        System.out.println("  " + r.sign);
        System.out.println("[HTTP response] " + r.code + "  " + r.body);
    }

    // ---- verdict ----

    /** Sign gate passed = NOT 401 sign verify failed (backend 408/502 etc. non-401 also counts). */
    static boolean signGatePassed(Resp r) {
        return !(r.code == 401 && r.body.contains("sign verify failed"));
    }

    static String tip(Resp r) {
        if (r.code == 408) return "  (HTTP 408 = backend route timeout, unrelated to sign verify; sign gate passed)";
        if (r.code == 200) return "  (HTTP 200, full success)";
        return "";
    }

    // ---- signature utils (pure JDK, identical to PaySignUtils) ----

    static String buildSignString(String method, String url, String ts, String nonce, String body) {
        String b = body == null ? "" : body;
        return method + "\n" + url + "\n" + ts + "\n" + nonce + "\n" + b + "\n";
    }

    static String sign(String signString, PrivateKey priv) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(signString.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static String newTs() { return String.valueOf(System.currentTimeMillis()); }

    static String newNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Load PKCS#8 private key from PEM text (strip header/footer/whitespace -> Base64 decode -> PKCS8 spec). */
    static PrivateKey loadPrivateKey(String pemPath) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(pemPath)), StandardCharsets.UTF_8);
        String content = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(content);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** HTTP response holder. */
    static final class Resp {
        final int code;
        final String body;
        final String signString;
        final String sign;
        Resp(int code, String body, String signString, String sign) {
            this.code = code; this.body = body; this.signString = signString; this.sign = sign;
        }
    }
}
