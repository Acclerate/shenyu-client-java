/*
 * PayRsaSignConfiguration —— 注册自定义 SignService Bean，替换默认 ComposableSignService。
 *
 * 关键机制：
 * 1. SignPluginConfiguration 用 @ConditionalOnMissingBean(SignService.class, search=ALL) 注册默认 Bean
 * 2. 本 @Bean SignService 先于条件判断被扫描到，默认 Bean 被跳过
 * 3. 此 jar 放 shenyu-bootstrap/ext-lib/，JVM 启动时进 classpath，Spring 启动期即可扫描
 */
package org.apache.shenyu.plugin.sign.custom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.shenyu.plugin.sign.service.SignService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PayRsaSignConfiguration.
 *
 * <p>注册 {@link PayRsaSignService} 为 Spring Bean，替代默认的 ComposableSignService。
 * 业务系统公钥从 classpath:biz-public-key.pem 加载。
 */
@Configuration
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /**
     * 自定义 SignService Bean。
     *
     * <p>此 Bean 注册后，SignPluginConfiguration 的默认 @Bean signService() 因
     * @ConditionalOnMissingBean(SignService.class) 而被跳过。
     *
     * @return PayRsaSignService 实例
     * @throws Exception 公钥加载失败
     */
    @Bean
    public SignService signService() throws Exception {
        PublicKey bizPublicKey = loadPublicKeyFromPem("biz-public-key.pem");
        LOG.info("[GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService");
        LOG.info("[GW-Sign] 业务公钥已加载，将用于验签入站请求的 X-Pay-Sign");
        return new PayRsaSignService(bizPublicKey);
    }

    /**
     * 从 classpath 加载 X.509 PEM 公钥。
     * 不依赖 shenyu-client-core 的 PemUtils（网关侧可能没有该依赖），自行实现。
     *
     * @param classpathLocation classpath 路径
     * @return PublicKey
     * @throws Exception 加载失败
     */
    private PublicKey loadPublicKeyFromPem(final String classpathLocation) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        try (InputStream in = resource.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            String pem = new String(out.toByteArray(), "UTF-8");
            // 去掉 PEM 头尾标记和换行
            String base64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }
}
