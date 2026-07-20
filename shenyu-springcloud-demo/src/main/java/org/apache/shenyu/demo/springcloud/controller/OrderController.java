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
import org.apache.shenyu.demo.springcloud.dto.OrderDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demo controller 注册到 ShenYu 网关，经 springCloud 插件 + Nacos discovery 路由转发.
 *
 * <p>接口面对齐官方 {@code shenyu-examples-springcloud} 的 OrderController
 * （<a href="https://github.com/apache/shenyu/blob/v2.6.1/shenyu-examples/shenyu-examples-springcloud/...">v2.6.1 source</a>），
 * 覆盖 4 类典型场景：
 * <ul>
 *   <li>{@link #save(OrderDTO)} —— POST + JSON 请求体反序列化为 DTO（验证网关 body 透传）</li>
 *   <li>{@link #findById(String)} —— GET + @RequestParam（最常见查询）</li>
 *   <li>{@link #getPathVariable(String, String)} —— GET + 多 @PathVariable（restful 多段路径变量）</li>
 *   <li>{@link #testRestFul(String)} —— GET + 单 @PathVariable（半 restful，官方用例 id+固定 name 段）</li>
 * </ul>
 *
 * <p>额外的 {@link #echoBody(String)} 用于验证网关对 raw string 请求体的透传完整性。
 *
 * <p>使用 ShenYu 2.6.1 的组合注解 {@link ShenyuRequestMapping} / {@link ShenyuGetMapping} /
 * {@link ShenyuPostMapping}（meta-annotated with {@code @ShenyuSpringCloudClient} + Spring 的
 * {@code @RequestMapping}/{@code @GetMapping}/{@code @PostMapping}），等价于同时声明 Spring MVC 路由
 * 与 ShenYu 注册元数据。
 *
 * <p>路由拼装：contextPath（{@code /springcloud-demo}） + 类级 path（{@code /order}） + 方法级 path。
 * 例：{@code GET /springcloud-demo/order/findById?id=42} → 经 shenyu-bootstrap:9196 转发到本实例。
 *
 * <p>响应体含 serverPort / instanceId，用于观测 springCloud 插件委托给 NacosDiscoveryClient 的分发行为。
 */
@RestController
@ShenyuRequestMapping("/order")
public class OrderController {

    /**
     * 进程级实例标识，整个 JVM 生命周期不变，用于区分「哪个实例响应」.
     */
    private static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);

    @Value("${server.port:8470}")
    private String serverPort;

    /**
     * Save order dto.
     *
     * <p>验证点：
     * <ol>
     *   <li>POST + JSON 请求体经网关透传到后端，Jackson 正确反序列化为 {@link OrderDTO}</li>
     *   <li>后端对 DTO 的修改（这里把 name 改成 "hello world spring cloud save order"）能原样回到客户端</li>
     * </ol>
     *
     * @param orderDTO the order dto from request body
     * @return the order dto with name modified
     */
    @ShenyuPostMapping("/save")
    public OrderDTO save(@RequestBody final OrderDTO orderDTO) {
        orderDTO.setName("hello world spring cloud save order");
        return orderDTO;
    }

    /**
     * Find by id order dto.
     *
     * <p>验证点：GET + @RequestParam 经网关透传 query string，后端正确绑定参数。
     *
     * @param id the id from query string
     * @return the order dto
     */
    @ShenyuGetMapping("/findById")
    public OrderDTO findById(@RequestParam("id") final String id) {
        return buildOrder(id, "hello world spring cloud findById");
    }

    /**
     * Gets path variable.
     *
     * <p>验证点：GET + 多 @PathVariable，URL 模板 {@code /path/{id}/{name}} 经网关透传后，
     * 后端按 Spring MVC 路径模板正确绑定。响应附 instanceId 用于多实例分发观测。
     *
     * @param id   the id from path segment
     * @param name the name from path segment
     * @return the order dto with instance metadata
     */
    @ShenyuGetMapping("/path/{id}/{name}")
    public Map<String, Object> getPathVariable(@PathVariable("id") final String id,
                                               @PathVariable("name") final String name) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("source", "springcloud-demo-path-variable");
        result.put("serverPort", serverPort);
        result.put("instanceId", INSTANCE_ID);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * Test rest ful order dto.
     *
     * <p>验证点：GET + 单 @PathVariable，URL 模板 {@code /path/{id}/name}（半 restful，
     * 后半段是固定字符串）。官方 demo 同名用例，常用来对照网关 path template 与后端是否一致。
     *
     * @param id the id from path segment
     * @return the order dto
     */
    @ShenyuGetMapping("/path/{id}/name")
    public OrderDTO testRestFul(@PathVariable("id") final String id) {
        return buildOrder(id, "hello world spring cloud restful inline " + id);
    }

    /**
     * Echo body —— 回显请求体大小与内容预览.
     *
     * <p>验证点：POST + raw string 请求体（text/plain 或 application/json），
     * 网关对 body 的字节透传完整性。本接口不在官方 demo 中，是本仓库额外补充的边界用例。
     *
     * @param body raw request body as string
     * @return size + preview of the body
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

    private OrderDTO buildOrder(final String id, final String name) {
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setId(id);
        orderDTO.setName(name);
        return orderDTO;
    }
}
