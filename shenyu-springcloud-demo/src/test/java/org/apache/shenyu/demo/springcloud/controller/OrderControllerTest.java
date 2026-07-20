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

import org.apache.shenyu.demo.springcloud.dto.OrderDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderController MockMvc 单元测试.
 *
 * <p>{@code @WebMvcTest(OrderController.class)} 只加载 web 层（Controller + Jackson 序列化器 + MockMvc
 * filter chain），<b>不</b>加载 {@code @EnableDiscoveryClient}、Nacos client、ShenYu 注册器等外部依赖，
 * 因此可在无 Nacos / 无 admin 的 CI 环境跑通，专注于验证 4 类网关场景的后端绑定逻辑。
 *
 * <p>覆盖官方 demo 的 4 个接口面：
 * <ol>
 *   <li>POST /order/save          —— JSON 请求体反序列化为 {@link OrderDTO}</li>
 *   <li>GET  /order/findById       —— @RequestParam 绑定</li>
 *   <li>GET  /order/path/{id}/{name} —— 多 @PathVariable 绑定</li>
 *   <li>GET  /order/path/{id}/name  —— 单 @PathVariable 绑定 + URL 模板与固定段混合</li>
 * </ol>
 * 外加本仓库额外补充的 echo-body raw string 透传用例。
 */
@WebMvcTest(OrderController.class)
@TestPropertySource(properties = {
        // @WebMvcTest 不会读 application.yml 里的 server.port，但 @Value("${server.port:8470}")
        // 在缺省时取 8470；这里显式声明便于断言。
        "server.port=8470"
})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * save 接口：POST + JSON body → 后端反序列化为 OrderDTO → 修改 name → 序列化回响应。
     *
     * <p>验证网关透传后端能正确反序列化，且后端的修改能反映到响应。
     */
    @Test
    void save_shouldEchoRequestBodyAndModifyName() throws Exception {
        String requestBody = "{\"id\":\"42\",\"name\":\"raw-name\"}";
        mockMvc.perform(post("/order/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("42"))
                // 后端把 name 改成固定串
                .andExpect(jsonPath("$.name").value("hello world spring cloud save order"));
    }

    /**
     * findById 接口：GET + @RequestParam。
     *
     * <p>验证 query string 经网关透传后端能正确绑定到方法参数。
     */
    @Test
    void findById_shouldReturnOrderWithGivenId() throws Exception {
        mockMvc.perform(get("/order/findById").param("id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("42"))
                .andExpect(jsonPath("$.name").value("hello world spring cloud findById"));
    }

    /**
     * getPathVariable 接口：GET + 多 @PathVariable。
     *
     * <p>验证多段路径变量经网关透传后，后端能按 Spring MVC 路径模板正确绑定。
     */
    @Test
    void getPathVariable_shouldBindMultiplePathSegments() throws Exception {
        mockMvc.perform(get("/order/path/42/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("42"))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.source").value("springcloud-demo-path-variable"))
                .andExpect(jsonPath("$.serverPort").value("8470"))
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * testRestFul 接口：GET + 单 @PathVariable + URL 模板与固定段混合（{@code /path/{id}/name}）。
     *
     * <p>验证后半段固定字符串 "name"（不是 PathVariable）路由命中正确，路径变量 id 绑定正确。
     */
    @Test
    void testRestFul_shouldBindSinglePathSegmentWithLiteralSuffix() throws Exception {
        mockMvc.perform(get("/order/path/42/name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("42"))
                .andExpect(jsonPath("$.name").value("hello world spring cloud restful inline 42"));
    }

    /**
     * echo-body 接口：POST + raw string body 透传。
     *
     * <p>验证后端能拿到完整请求体字节，bodyPreview 字段反映原始内容。
     */
    @Test
    void echoBody_shouldEchoRawStringBody() throws Exception {
        String body = "{\"k\":\"v\"}";
        mockMvc.perform(post("/order/echo-body")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverPort").value("8470"))
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.receivedBodyBytes").value(body.getBytes().length))
                .andExpect(jsonPath("$.bodyPreview").value(body));
    }

    /**
     * echo-body 接口边界：空 body（{@code @RequestBody(required = false)}）。
     */
    @Test
    void echoBody_shouldHandleEmptyBody() throws Exception {
        mockMvc.perform(post("/order/echo-body"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBodyBytes").value(0))
                .andExpect(jsonPath("$.bodyPreview").value(""));
    }
}
