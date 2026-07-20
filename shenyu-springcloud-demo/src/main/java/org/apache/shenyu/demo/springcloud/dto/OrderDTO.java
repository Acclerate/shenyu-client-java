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

package org.apache.shenyu.demo.springcloud.dto;

import java.util.Objects;

/**
 * 订单 DTO，与 ShenYu 官方 shenyu-examples-springcloud 字段对齐（id + name）.
 *
 * <p>不引入 Lombok，避免单 demo 模块增加额外编译期依赖；getter/setter 手写。
 * 序列化走 Jackson 默认（Spring Boot web starter 已提供），网关侧会原样透传 JSON。
 */
public class OrderDTO {

    /**
     * 订单 ID.
     */
    private String id;

    /**
     * 订单名称.
     */
    private String name;

    /**
     * Default constructor for JSON deserialization.
     */
    public OrderDTO() {
    }

    /**
     * All-args constructor for test convenience.
     *
     * @param id   order id
     * @param name order name
     */
    public OrderDTO(final String id, final String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * Gets id.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * Sets id.
     *
     * @param id the id
     */
    public void setId(final String id) {
        this.id = id;
    }

    /**
     * Gets name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets name.
     *
     * @param name the name
     */
    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderDTO)) {
            return false;
        }
        OrderDTO orderDTO = (OrderDTO) o;
        return Objects.equals(id, orderDTO.id) && Objects.equals(name, orderDTO.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    @Override
    public String toString() {
        return "OrderDTO{id='" + id + "', name='" + name + "'}";
    }
}
