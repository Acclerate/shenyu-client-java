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

package org.apache.shenyu.demo.http.controller;

import org.apache.shenyu.client.springmvc.annotation.ShenyuSpringMvcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demo controller registered into ShenYu gateway via {@link ShenyuSpringMvcClient}.
 *
 * <p>Class-level annotation path acts as a prefix on the gateway side. The actual upstream
 * path the gateway forwards to is the real Spring MVC path of the method
 * (i.e. {@code /order/findById}), combined with the registered {@code contextPath}
 * ({@code /http-demo11}) so the gateway route becomes {@code /http-demo11/order/findById}.
 *
 * <p>改造说明（divide 插件验证专用）：
 * <ul>
 *   <li>findById / hello 返回值增加 serverPort / instanceId / timestamp，用于观测负载均衡分发</li>
 *   <li>新增 delay 接口 —— Thread.sleep，配合 rule timeout 制造超时，验证重试策略</li>
 *   <li>新增 echo-headers 接口 —— 回显全部请求头，验证 header 条件匹配 + headerMaxSize 拦截</li>
 *   <li>新增 echo-body 接口 —— 回显请求体大小，验证 requestMaxSize 拦截</li>
 * </ul>
 */
@RestController
@RequestMapping("/order")
@ShenyuSpringMvcClient(path = "/order/**")
public class OrderController {

    /**
     * 进程级实例标识，整个 JVM 生命周期不变，用于区分「哪个实例响应」。
     * 2 个实例的 instanceId 不同，即使 serverPort 相同也可区分。
     */
    private static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);

    @Value("${server.port:8380}")
    private String serverPort;

    /**
     * Find order by id.
     *
     * <p>返回值含 serverPort / instanceId / timestamp，用于观测负载均衡策略下的分发行为。
     *
     * @param id order id
     * @return order info with instance identity
     */
    @GetMapping("/findById")
    @ShenyuSpringMvcClient(path = "/findById", ruleName = "/findById")
    public Map<String, Object> findById(@RequestParam("id") final String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", "demo-order-" + id);
        result.put("source", "shenyu-http-demo");
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * Hello endpoint.
     *
     * @return greeting with instance identity
     */
    @GetMapping("/hello")
    @ShenyuSpringMvcClient(path = "/hello", ruleName = "/hello")
    public Map<String, Object> hello() {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Hello from shenyu-http-demo");
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        return result;
    }

    /**
     * Delay endpoint —— 故意 sleep 指定毫秒数。
     *
     * <p>用途：配合 divide 规则的 {@code timeout} 字段制造超时，验证重试策略（current / failover）。
     * 示例：rule timeout=1000，请求 delay?ms=3000 必定触发超时重试。
     *
     * @param ms 睡眠毫秒数，默认 2000
     * @return 响应体含 serverPort / instanceId / 实际睡眠时间
     * @throws InterruptedException sleep interrupted
     */
    @GetMapping("/delay")
    @ShenyuSpringMvcClient(path = "/delay", ruleName = "/delay")
    public Map<String, Object> delay(
            @RequestParam(value = "ms", defaultValue = "2000") final long ms)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(ms);
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("requestedMs", ms);
        result.put("actualMs", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Echo headers —— 回显所有请求头。
     *
     * <p>用途：
     * <ol>
     *   <li>验证 divide 规则/选择器的 header 条件匹配（灰度路由），请求到达此接口说明通过了网关转发</li>
     *   <li>验证 headerMaxSize：超限时网关直接拒绝（4xx），该接口收不到请求</li>
     * </ol>
     *
     * @param request HTTP 请求
     * @return 含 serverPort、所有请求头 Map、请求头数量
     */
    @GetMapping("/echo-headers")
    @ShenyuSpringMvcClient(path = "/echo-headers", ruleName = "/echo-headers")
    public Map<String, Object> echoHeaders(final HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            Collections.list(names).forEach(name -> headers.put(name, request.getHeader(name)));
        }
        // 计算请求头总字节数（估算：name=value\r\n）
        int estimatedBytes = headers.entrySet().stream()
                .mapToInt(e -> e.getKey().length() + 2 + e.getValue().length() + 2)
                .sum();
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("headerCount", headers.size());
        result.put("estimatedHeaderBytes", estimatedBytes);
        result.put("receivedHeaders", headers);
        return result;
    }

    /**
     * Echo body —— 回显请求体大小与内容预览。
     *
     * <p>用途：验证 divide 规则的 requestMaxSize 字段。
     * 超限时网关直接返回 413，该接口收不到请求。
     *
     * @param body 请求体（text/plain 或 application/json）
     * @return 含 serverPort、请求体字节数、前 200 字节预览
     */
    @PostMapping("/echo-body")
    @ShenyuSpringMvcClient(path = "/echo-body", ruleName = "/echo-body")
    public Map<String, Object> echoBody(
            @RequestBody(required = false) final String body) {
        int len = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("receivedBodyBytes", len);
        result.put("bodyPreview", body == null ? ""
                : body.substring(0, Math.min(200, body.length())));
        return result;
    }
}
