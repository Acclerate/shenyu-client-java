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

package org.apache.shenyu.demo.springcloud.controller;

import org.apache.shenyu.client.springcloud.annotation.ShenyuGetMapping;
import org.apache.shenyu.client.springcloud.annotation.ShenyuPostMapping;
import org.apache.shenyu.client.springcloud.annotation.ShenyuRequestMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demo controller 注册到 ShenYu 网关，经 springCloud 插件 + Nacos discovery 路由转发.
 *
 * <p>使用 ShenYu 2.6.1 的组合注解 {@link ShenyuRequestMapping} / {@link ShenyuGetMapping} /
 * {@link ShenyuPostMapping}（meta-annotated with {@code @ShenyuSpringCloudClient} + Spring 的
 * {@code @RequestMapping}/{@code @GetMapping}/{@code @PostMapping}），等价于同时声明 Spring MVC 路由
 * 与 ShenYu 注册元数据。
 *
 * <p>路由拼装：contextPath（{@code /springcloud-demo}，来自 application.yml
 * {@code shenyu.client.springCloud.props.contextPath}） + 类级 path（{@code /order}） + 方法级 path。
 * 例：{@code GET /springcloud-demo/order/findById?id=42} → 经 shenyu-bootstrap:9196 转发到本实例。
 *
 * <p>响应体含 serverPort / instanceId，用于观测 springCloud 插件委托给 NacosDiscoveryClient 的分发行为。
 */
@org.springframework.web.bind.annotation.RestController
@ShenyuRequestMapping("/order")
public class OrderController {

    /**
     * 进程级实例标识，整个 JVM 生命周期不变，用于区分「哪个实例响应」.
     */
    private static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);

    @Value("${server.port:8470}")
    private String serverPort;

    /**
     * Find order by id.
     *
     * <p>响应体含 serverPort / instanceId / timestamp，便于观测 springCloud 插件路由命中哪个 Nacos 实例。
     *
     * @param id order id
     * @return order info with instance identity
     */
    @ShenyuGetMapping("/findById")
    public Map<String, Object> findById(@RequestParam("id") final String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", "springcloud-demo-order-" + id);
        result.put("source", "shenyu-springcloud-demo");
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
    @ShenyuGetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Hello from shenyu-springcloud-demo");
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        return result;
    }

    /**
     * Echo body —— 回显请求体大小与内容预览.
     *
     * <p>用于验证 springCloud 插件对 POST + 请求体的转发是否完整。
     *
     * @param body 请求体（text/plain 或 application/json）
     * @return 含 serverPort、请求体字节数、前 200 字节预览
     */
    @ShenyuPostMapping("/echo-body")
    public Map<String, Object> echoBody(@RequestBody(required = false) final String body) {
        int len = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        Map<String, Object> result = new HashMap<>();
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("receivedBodyBytes", len);
        result.put("bodyPreview", body == null ? "" : body.substring(0, Math.min(200, body.length())));
        return result;
    }
}
