/*
 * PayRsaSignConfiguration —— SPI 自动装配入口。
 *
 * 注册三个 Bean：
 *   - SignCacheBizPublicKeyProvider（公钥提供器 + AuthDataSubscriber 单订阅者）
 *   - AppAuthHealthIndicator（actuator 就绪检查）
 *   - PayRsaSignService（替换 ShenYu 原生 ComposableSignService）
 *
 * @AutoConfigureBefore（铁律 7，P0 加固）：强制本 Configuration 在 ShenYu 官方
 *   SignPluginConfiguration 之前被 Spring Boot 解析。官方的 @Bean signService 带有
 *   @ConditionalOnMissingBean(SignService.class, search=ALL)，先解析本类后容器已有
 *   PayRsaSignService，官方的 ComposableSignService 不会注册，消除双 Bean 风险。
 *
 * 此 jar 放 shenyu-bootstrap/ext-lib/，通过 META-INF/spring.factories 触发加载。
 * 不使用 @Component（bootstrap 主类在 org.apache.shenyu.bootstrap，扫不到本包，铁律 2）。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.service.SignService;
import org.apache.shenyu.springboot.starter.plugin.sign.SignPluginConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureBefore(SignPluginConfiguration.class)
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /**
     * 公钥提供器（同时是 AuthDataSubscriber 单订阅者）。
     *
     * <p>作为 {@link BizPublicKeyProvider} 注入给 {@link PayRsaSignService}；
     * 作为 {@code AuthDataSubscriber} 被 ShenYu sync 层的
     * {@code ObjectProvider<List<AuthDataSubscriber>>} 自动收集，接收 admin 推送。
     */
    @Bean
    public SignCacheBizPublicKeyProvider signCacheBizPublicKeyProvider() {
        LOG.info("[GW-Sign] SignCacheBizPublicKeyProvider 已注册（公钥源 = app_auth, "
                + "websocket push, 零轮询, 单订阅者）");
        return new SignCacheBizPublicKeyProvider();
    }

    /**
     * 就绪检查指标，配合 K8s readinessProbe。
     */
    @Bean
    public AppAuthHealthIndicator appAuthHealthIndicator(
            final SignCacheBizPublicKeyProvider signCacheBizPublicKeyProvider) {
        LOG.info("[GW-Sign] AppAuthHealthIndicator 已注册（/actuator/health 接入就绪检查）");
        return new AppAuthHealthIndicator(signCacheBizPublicKeyProvider);
    }

    /**
     * 验签服务，替换原生 ComposableSignService。
     *
     * <p>配合类上的 {@code @AutoConfigureBefore}，先于官方注册，官方的
     * {@code @ConditionalOnMissingBean(SignService.class, search=ALL)} 命中跳过。
     */
    @Bean
    public SignService payRsaSignService(final BizPublicKeyProvider bizPublicKeyProvider) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册（替换原生 ComposableSignService）");
        return new PayRsaSignService(bizPublicKeyProvider);
    }
}
