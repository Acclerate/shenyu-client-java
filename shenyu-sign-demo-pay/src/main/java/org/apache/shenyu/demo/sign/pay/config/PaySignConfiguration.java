/*
 * PAY 侧密钥加载配置：只加载 2 个密钥 Bean。
 */
package org.apache.shenyu.demo.sign.pay.config;

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
 * <p>启动时加载 PAY 侧所需的 2 个密钥：
 * <ul>
 *   <li>payPrivateKey —— 支付服务私钥，用于加签响应/回调</li>
 *   <li>bizPublicKey  —— 业务系统公钥，用于验签入站请求</li>
 * </ul>
 * 不加载 biz-private-key 和 pay-public-key（那些属于 BIZ 进程）。
 */
@Configuration
@EnableConfigurationProperties(PaySignProperties.class)
public class PaySignConfiguration {

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
     * 业务系统公钥（验签入站请求）。
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
