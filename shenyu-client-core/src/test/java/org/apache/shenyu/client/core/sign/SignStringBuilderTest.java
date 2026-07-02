/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.client.core.sign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SignStringBuilderTest.
 *
 * <p>以需求文档（附件一）公开的 4 个请求示例 + 响应示例作为<b>确定性锚点</b>，
 * 逐一断言构造出的待签名串与文档完全一致。
 */
class SignStringBuilderTest {

    private static final String TS = "1554208460";

    private static final String NONCE = "593BEC0C930BF1AFEB40B4A08C8FB242";

    @Test
    void shouldBuildRequestSignStringForPostWithJsonBody() {
        // 示例一：POST + JSON body
        String body = "{\"appid\":\"wxd678efh567hg6787\",\"mchid\":\"1900007291\",\"description\":\"Image形象店-深圳腾大-QQ公仔\",\"out_trade_no\":\"1217752501201407033233368018\",\"notify_url\":\"https://www.weixin.qq.com/wxpay/pay.php\",\"amount\":{\"total\":100,\"currency\":\"CNY\"},\"payer\":{\"openid\":\"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o\"}}";
        String expected = "POST\n"
                + "/v3/pay/transactions/jsapi\n"
                + TS + "\n"
                + NONCE + "\n"
                + body + "\n";
        String actual = SignStringBuilder.buildRequestSignString("POST", "/v3/pay/transactions/jsapi", TS, NONCE, body);
        assertEquals(expected, actual);
    }

    @Test
    void shouldBuildRequestSignStringForGetWithoutQuery() {
        // 示例二：GET 无 query，body 为空
        String expected = "GET\n"
                + "/v3/certificates\n"
                + TS + "\n"
                + NONCE + "\n"
                + "\n";
        String actual = SignStringBuilder.buildRequestSignString("GET", "/v3/certificates", TS, NONCE, null);
        assertEquals(expected, actual);
    }

    @Test
    void shouldBuildRequestSignStringForGetWithQuery() {
        // 示例三：GET + query string（URL 含 ?limit=5&offset=10...）
        String url = "/v3/marketing/partnerships?limit=5&offset=10"
                + "&authorized_data=%7B%22business_type%22%3A%22FAVOR_STOCK%22%2C%22stock_id%22%3A%222433405%22%7D"
                + "&partner=%7B%22type%22%3A%22APPID%22%2C%22appid%22%3A%22wx4e1916a585d1f4e9%22%2C%22merchant_id%22%3A%222480029552%22%7D";
        String expected = "GET\n"
                + url + "\n"
                + TS + "\n"
                + NONCE + "\n"
                + "\n";
        String actual = SignStringBuilder.buildRequestSignString("GET", url, TS, NONCE, "");
        assertEquals(expected, actual);
    }

    @Test
    void shouldBuildRequestSignStringForGetWithPathParam() {
        // 示例四：GET + path 参数
        String expected = "GET\n"
                + "/v3/refund/domestic/refunds/123123123123\n"
                + TS + "\n"
                + NONCE + "\n"
                + "\n";
        String actual = SignStringBuilder.buildRequestSignString("GET", "/v3/refund/domestic/refunds/123123123123", TS, NONCE, null);
        assertEquals(expected, actual);
    }

    @Test
    void shouldBuildResponseSignStringForVerify() {
        // 响应验签：3 行格式（附件二示例）
        String timestamp = "1722850421";
        String nonce = "d824f2e086d3c1df967785d13fcd22ef";
        String body = "{\"code_url\":\"weixin://wxpay/bizpayurl?pr=JyC91EIz1\"}";
        String expected = "1722850421\nd824f2e086d3c1df967785d13fcd22ef\n" + body + "\n";
        String actual = SignStringBuilder.buildResponseSignString(timestamp, nonce, body);
        assertEquals(expected, actual);
    }

    @Test
    void shouldBuildResponseSignStringWithEmptyBody() {
        // HTTP 204 No Content，最后一行仅为一个 \n
        String expected = "1722850421\nd824f2e086d3c1df967785d13fcd22ef\n\n";
        String actual = SignStringBuilder.buildResponseSignString("1722850421", "d824f2e086d3c1df967785d13fcd22ef", "");
        assertEquals(expected, actual);
    }
}
