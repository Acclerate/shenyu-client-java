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

package org.apache.shenyu.demo.sign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ShenyuSignDemoApplication.
 *
 * <p>支付加签验签本地闭环 Demo 启动类。单进程承载 BIZ（业务系统）+ PAY（支付服务）双角色。
 *
 * <p>启动后访问：
 * <ul>
 *   <li>{@code curl http://localhost:8390/biz/pay} —— 跑通请求加签→请求验签→响应加签→响应验签</li>
 *   <li>{@code curl -X POST http://localhost:8390/v3/pay/notify-trigger} —— 触发回调加签→回调验签</li>
 * </ul>
 */
@SpringBootApplication
public class ShenyuSignDemoApplication {

    /**
     * Main entry.
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(ShenyuSignDemoApplication.class, args);
    }
}
