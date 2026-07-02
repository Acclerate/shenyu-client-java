/*
 * BIZ 出站客户端：OkHttp + PaySignInterceptor 自动加签，收到响应后用 pay-public 验签。
 */
package org.apache.shenyu.demo.sign.biz.client;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.shenyu.demo.sign.biz.security.PaySignInterceptor;
import org.apache.shenyu.demo.sign.biz.security.PaySignVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * PaySignClient.
 *
 * <p>BIZ 角色（业务系统）出站客户端：注入 {@link PaySignInterceptor} 自动对每个请求加签，
 * 收到响应后用支付服务公钥验签。
 *
 * <p>URL 由 BizController 从配置传入：
 * <ul>
 *   <li>阶段1直连：http://localhost:8392/v3/pay/transactions/jsapi</li>
 *   <li>阶段2/3经网关：http://localhost:9196/pay-demo/v3/pay/transactions/jsapi</li>
 * </ul>
 */
@Component
public class PaySignClient {

    private static final Logger LOG = LoggerFactory.getLogger(PaySignClient.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final PublicKey payPublicKey;

    public PaySignClient(@Qualifier("bizPrivateKey") final PrivateKey bizPrivateKey,
                         @Qualifier("payPublicKey") final PublicKey payPublicKey) {
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(new PaySignInterceptor(bizPrivateKey))
                .build();
        this.payPublicKey = payPublicKey;
    }

    /**
     * 发送加签请求并验签响应。
     *
     * @param url      支付服务接口 URL（直连或经网关，由调用方决定）
     * @param jsonBody 请求体（JSON）
     * @return 含原始响应体与验签结果的载体
     * @throws Exception IO 或验签异常
     */
    public PayResponse pay(final String url, final String jsonBody) throws Exception {
        RequestBody body = RequestBody.create(JSON, jsonBody);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        LOG.info("[BIZ] 发起支付请求 url={} bodyLen={}", url, jsonBody.length());

        try (Response response = httpClient.newCall(request).execute()) {
            LOG.info("[BIZ] 响应已接收 httpCode={}", response.code());
            int code = response.code();
            PaySignVerifier.VerifyResult verifyResult = PaySignVerifier.verifyResponse(response, payPublicKey);
            PaySignInterceptor.SignContext signCtx = PaySignInterceptor.lastSignContext();
            LOG.info("[BIZ] 收到响应 httpCode={} 响应验签={}", code, verifyResult.isPass() ? "通过" : "失败");
            PayResponse resp = new PayResponse(code, verifyResult.getBody(), verifyResult.isPass(),
                    verifyResult.getTimestamp(), verifyResult.getNonce(), verifyResult.getSign(),
                    jsonBody, signCtx);
            return resp;
        } finally {
            PaySignInterceptor.clearSignContext();
        }
    }

    /**
     * PayResponse.
     */
    public static final class PayResponse {

        private final int httpCode;

        private final String body;

        private final boolean verifyPass;

        private final String timestamp;

        private final String nonce;

        private final String sign;

        private final String requestBody;

        private final transient PaySignInterceptor.SignContext signContext;

        PayResponse(final int httpCode, final String body, final boolean verifyPass,
                    final String timestamp, final String nonce, final String sign,
                    final String requestBody, final PaySignInterceptor.SignContext signContext) {
            this.httpCode = httpCode;
            this.body = body;
            this.verifyPass = verifyPass;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.sign = sign;
            this.requestBody = requestBody;
            this.signContext = signContext;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getBody() {
            return body;
        }

        public boolean isVerifyPass() {
            return verifyPass;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getNonce() {
            return nonce;
        }

        public String getSign() {
            return sign;
        }

        public String getRequestBody() {
            return requestBody;
        }

        public PaySignInterceptor.SignContext getSignContext() {
            return signContext;
        }
    }
}
