/*
 * PayRsaSignConfiguration —— 注册自定义 SignService Bean，替换默认 ComposableSignService。
 *
 * 关键机制：
 * 1. SignPluginConfiguration 用 @ConditionalOnMissingBean(SignService.class, search=ALL) 注册默认 Bean
 * 2. 本 @Bean SignService 先于条件判断被扫描到，默认 Bean 被跳过
 * 3. 此 jar 放 shenyu-bootstrap/ext-lib/，JVM 启动时进 classpath，Spring 启动期即可扫描
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.service.SignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * PayRsaSignConfiguration.
 *
 * <p>注册 {@link PayRsaSignService} 为 Spring Bean，替代默认的 ComposableSignService。
 * 支持公钥从 classpath 或 Redis 读取（用于密钥轮换）。
 */
@Configuration
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /**
     * 业务公钥提供器。
     */
    @Bean
    public DynamicBizPublicKeyProvider bizPublicKeyProvider(final Environment env) {
        String source = getString(env, "gw.sign.key-source", "classpath");
        String classpathLocation = getString(env, "gw.sign.classpath-public-key", "biz-public-key.pem");
        String redisHost = getString(env, "gw.sign.redis.host", "127.0.0.1");
        int redisPort = getInt(env, "gw.sign.redis.port", 6379);
        String redisPassword = getString(env, "gw.sign.redis.password", "");
        int redisDatabase = getInt(env, "gw.sign.redis.database", 0);
        long redisTimeoutMs = getLong(env, "gw.sign.redis.timeout-ms", 1500L);
        String redisKeyPattern = getString(env, "gw.sign.redis.biz-public-key-pattern", "shenyu:sign:%s:biz-public-key.pem");
        long cacheTtlSeconds = getLong(env, "gw.sign.cache.ttl-seconds", 30L);
        long retrySeconds = getLong(env, "gw.sign.cache.failure-retry-seconds", 3L);
        boolean allowStale = getBoolean(env, "gw.sign.cache.allow-stale-on-refresh-failure", true);
        return new DynamicBizPublicKeyProvider(
                source, classpathLocation,
                redisHost, redisPort, redisPassword, redisDatabase, redisTimeoutMs,
                redisKeyPattern, cacheTtlSeconds, retrySeconds, allowStale
        );
    }

    /**
     * 自定义 SignService Bean。
     *
     * <p>此 Bean 注册后，SignPluginConfiguration 的默认 @Bean signService() 因
     * @ConditionalOnMissingBean(SignService.class) 而被跳过。
     */
    @Bean
    public SignService signService(final DynamicBizPublicKeyProvider bizPublicKeyProvider) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册，替换默认 ComposableSignService");
        return new PayRsaSignService(bizPublicKeyProvider);
    }

    private String getString(final Environment env, final String key, final String defaultValue) {
        String value = env.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }

    private int getInt(final Environment env, final String key, final int defaultValue) {
        String value = env.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private long getLong(final Environment env, final String key, final long defaultValue) {
        String value = env.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean getBoolean(final Environment env, final String key, final boolean defaultValue) {
        String value = env.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
