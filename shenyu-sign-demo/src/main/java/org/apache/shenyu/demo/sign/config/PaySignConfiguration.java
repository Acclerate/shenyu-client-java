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

package org.apache.shenyu.demo.sign.config;

import org.apache.shenyu.client.core.sign.PemUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * PaySignConfiguration.
 *
 * <p>启动时加载 4 个密钥为 Bean，供 BIZ/PAY 双角色复用。
 */
@Configuration
@EnableConfigurationProperties(PaySignProperties.class)
public class PaySignConfiguration {

    /**
     * 业务系统私钥（加签出站请求）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 私钥
     * @throws Exception 加载失败
     */
    @Bean("bizPrivateKey")
    public PrivateKey bizPrivateKey(final PaySignProperties props, final ResourceLoader loader) throws Exception {
        return PemUtils.loadPrivateKeyFromPem(loadResource(loader, props.getBizPrivateKey()));
    }

    /**
     * 业务系统公钥（支付服务验签入站请求）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 公钥
     * @throws Exception 加载失败
     */
    @Bean("bizPublicKey")
    public PublicKey bizPublicKey(final PaySignProperties props, final ResourceLoader loader) throws Exception {
        return PemUtils.loadPublicKeyFromPem(loadResource(loader, props.getBizPublicKey()));
    }

    /**
     * 支付服务私钥（加签响应/回调）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 私钥
     * @throws Exception 加载失败
     */
    @Bean("payPrivateKey")
    public PrivateKey payPrivateKey(final PaySignProperties props, final ResourceLoader loader) throws Exception {
        return PemUtils.loadPrivateKeyFromPem(loadResource(loader, props.getPayPrivateKey()));
    }

    /**
     * 支付服务公钥（业务系统验签响应/回调）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 公钥
     * @throws Exception 加载失败
     */
    @Bean("payPublicKey")
    public PublicKey payPublicKey(final PaySignProperties props, final ResourceLoader loader) throws Exception {
        return PemUtils.loadPublicKeyFromPem(loadResource(loader, props.getPayPublicKey()));
    }

    private String loadResource(final ResourceLoader loader, final String location) throws Exception {
        Resource resource = loader.getResource(location);
        try (java.io.InputStream in = resource.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
