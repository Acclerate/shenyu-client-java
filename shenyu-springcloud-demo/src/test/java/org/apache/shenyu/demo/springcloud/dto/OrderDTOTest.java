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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OrderDTO POJO 测试，覆盖 getter/setter/all-args 构造/equals/hashCode/toString.
 *
 * <p>Jackson 序列化由 Spring Boot 自动配置，不在此直接断言（序列化逻辑由 MockMvc 测试覆盖）。
 */
class OrderDTOTest {

    @Test
    void defaultConstructorShouldCreateEmptyInstance() {
        OrderDTO dto = new OrderDTO();
        assertNull(dto.getId());
        assertNull(dto.getName());
    }

    @Test
    void allArgsConstructorShouldSetFields() {
        OrderDTO dto = new OrderDTO("42", "test-order");
        assertEquals("42", dto.getId());
        assertEquals("test-order", dto.getName());
    }

    @Test
    void settersShouldUpdateFields() {
        OrderDTO dto = new OrderDTO();
        dto.setId("100");
        dto.setName("updated");
        assertEquals("100", dto.getId());
        assertEquals("updated", dto.getName());
    }

    @Test
    void equalsAndHashCodeShouldFollowContract() {
        OrderDTO a = new OrderDTO("42", "alice");
        OrderDTO b = new OrderDTO("42", "alice");
        OrderDTO c = new OrderDTO("42", "bob");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "not-an-order");
        assertEquals(a, a);
    }

    @Test
    void toStringShouldContainIdAndName() {
        OrderDTO dto = new OrderDTO("42", "alice");
        String s = dto.toString();
        // 不强约束格式，只断言两个字段值出现
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("42"), "toString 应包含 id 值");
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("alice"), "toString 应包含 name 值");
    }
}
