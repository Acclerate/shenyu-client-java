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

package org.apache.shenyu.demo.sign.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * NotifySender.
 *
 * <p>支付服务主动发起回调通知（用 PayService 构造的加签头 + 通知体 POST 给业务系统回调地址）。
 * 对应需求文档「验签」第二个时序图：PAY 加签 → BIZ 验签。
 */
@Component
public class NotifySender {

    private static final Logger LOG = LoggerFactory.getLogger(NotifySender.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * 发送回调通知。
     *
     * @param callbackUrl 业务系统回调地址
     * @param body        通知体
     * @param signHeaders 加签头（X-Pay-Timestamp/X-Pay-Nonce/X-Pay-Sign）
     * @return 业务系统响应的 HTTP 状态码
     * @throws Exception IO 异常
     */
    public int send(final String callbackUrl, final String body, final java.util.Map<String, String> signHeaders)
            throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(callbackUrl)
                .post(RequestBody.create(JSON, body));
        signHeaders.forEach(builder::addHeader);
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            LOG.info("[PAY] 回调通知已发送 → {} respCode={}", callbackUrl, response.code());
            return response.code();
        }
    }
}
