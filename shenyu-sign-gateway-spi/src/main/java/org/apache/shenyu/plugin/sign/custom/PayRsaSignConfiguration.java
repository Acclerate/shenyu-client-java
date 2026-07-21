/*
 * PayRsaSignConfiguration —— SPI 自动装配入口。
 *
 * 注册 AdminConfigBizPublicKeyProvider 和 PayRsaSignService（替换 ShenYu 原生
 * ComposableSignService，通过 @ConditionalOnMissingBean 机制）。
 *
 * 此 jar 放 shenyu-bootstrap/ext-lib/，通过 META-INF/spring.factories 触发加载。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.service.SignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /** 后台从 BaseDataCache 同步公钥的周期配置项（秒） */
    private static final String REFRESH_INTERVAL_KEY = "gw.springcloud.refresh-interval-seconds";

    /** 同步周期默认值（秒） */
    private static final long DEFAULT_REFRESH_INTERVAL_SECONDS = 30L;

    @Bean
    public AdminConfigBizPublicKeyProvider bizPublicKeyProvider(final Environment env) {
        final long refreshInterval = readRefreshIntervalSeconds(env, REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL_SECONDS);
        return new AdminConfigBizPublicKeyProvider(refreshInterval);
    }

    @Bean
    public SignService signService(final AdminConfigBizPublicKeyProvider provider) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册（公钥源 = admin plugin.config gw.springcloud.app-key.*）");
        return new PayRsaSignService(provider);
    }

    /**
     * 读取长整型配置项；缺失或非法时回退默认值。
     */
    private static long readRefreshIntervalSeconds(final Environment env, final String key, final long defaultValue) {
        final String raw = env.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ignored) {
            LOG.warn("[GW-Sign] 配置项 {} 值非法（{}），使用默认值 {}s", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
