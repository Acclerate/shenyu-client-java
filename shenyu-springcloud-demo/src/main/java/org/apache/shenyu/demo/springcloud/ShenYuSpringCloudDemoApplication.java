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

package org.apache.shenyu.demo.springcloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Cloud + ShenYu springCloud plugin demo 启动类.
 *
 * <p>双通道并存：
 * <ul>
 *   <li>通道① {@code shenyu.register.registerType=http}：HTTP 元数据推 admin（@ShenyuSpringCloudClient 的 path 元数据）</li>
 *   <li>通道③ spring-cloud-starter-alibaba-nacos-discovery：本服务实例注册到 Nacos，由 shenyu-bootstrap 的
 *       NacosDiscoveryClient 拉取并用于 springCloud 插件路由转发</li>
 * </ul>
 *
 * <p>{@link EnableDiscoveryClient} 显式声明便于排查；Spring Cloud Commons 2.x+ 即便不写也会自动开启。
 * 服务名（Nacos serviceId）取自 {@code spring.application.name=shenyu-springcloud-demo}，
 * 该名同时也是 admin springCloud selector handle 中的 {@code serviceId}。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ShenYuSpringCloudDemoApplication {

    /**
     * Demo entry.
     *
     * @param args program args
     */
    public static void main(final String[] args) {
        SpringApplication.run(ShenYuSpringCloudDemoApplication.class, args);
    }
}
