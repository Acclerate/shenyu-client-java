/*
 * BIZ 侧密钥加载配置：只加载 2 个密钥 Bean。
 */
package org.apache.shenyu.demo.sign.biz.config;

import org.apache.shenyu.demo.sign.biz.security.PemUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * BizSignConfiguration.
 *
 * <p>启动时加载 BIZ 侧所需的 2 个密钥：
 * <ul>
 *   <li>bizPrivateKey —— 业务系统私钥，用于对出站请求加签</li>
 *   <li>payPublicKey  —— 支付服务公钥，用于验签响应/回调</li>
 * </ul>
 * 不加载 pay-private-key 和 biz-public-key（那些属于 PAY 进程）。
 */
@Configuration
@EnableConfigurationProperties(BizSignProperties.class)
public class BizSignConfiguration {

    /**
     * 业务系统私钥（加签出站请求）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 私钥
     * @throws Exception 加载失败
     */
    @Bean("bizPrivateKey")
    public PrivateKey bizPrivateKey(final BizSignProperties props, final ResourceLoader loader) throws Exception {
        return PemUtils.loadPrivateKeyFromPem(loadResource(loader, props.getBizPrivateKey()));
    }

    /**
     * 支付服务公钥（验签响应/回调）。
     *
     * @param props  配置
     * @param loader 资源加载器
     * @return 公钥
     * @throws Exception 加载失败
     */
    @Bean("payPublicKey")
    public PublicKey payPublicKey(final BizSignProperties props, final ResourceLoader loader) throws Exception {
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
