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

package org.apache.shenyu.demo.springcloud.sign;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务方验签公钥服务接口。
 *
 * <p><b>关键：不标 {@code @ShenyuSpringCloudClient}</b>，因此该接口不会被注册到 admin，
 * 网关 springCloud 插件也绝对不会把外部流量路由到 {@code /sign/public-key/**}。
 * 它仅作为 demo 自身端口（默认 8470）的一个内部服务，供网关侧 SPI 通过
 * Apache HttpClient 直连调用，避免走网关 sign 插件形成循环验签。
 *
 * <p>契约：{@code GET /sign/public-key/{appKey}}
 * <ul>
 *   <li>200：返回 {@link PublicKeyInfo}（含 PEM 公钥 + fingerprint）</li>
 *   <li>404：appKey 不存在（{@code error=app_key_not_found}）</li>
 * </ul>
 */
@RestController
@RequestMapping("/sign/public-key")
public class SignController {

    private final PublicKeyStore publicKeyStore;

    public SignController(final PublicKeyStore publicKeyStore) {
        this.publicKeyStore = publicKeyStore;
    }

    @GetMapping("/{appKey}")
    public ResponseEntity<PublicKeyInfo> getPublicKey(@PathVariable final String appKey) {
        PublicKeyInfo info = publicKeyStore.get(appKey);
        if (info == null) {
            return ResponseEntity.status(404)
                    .body(PublicKeyInfo.error("app_key_not_found", appKey, "no public key configured for appKey"));
        }
        return ResponseEntity.ok(info);
    }
}
