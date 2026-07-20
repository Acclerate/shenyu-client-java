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
 * 支持公钥从 classpath 或 HTTP（demo 提供的公钥接口）读取（用于密钥轮换）。
 */
@Configuration
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /**
     * 业务公钥提供器（HTTP 源）。
     *
     * <p>构造完成后立即预热指定 appKey 的公钥缓存（在 Spring 启动线程上同步拉取，
     * 不阻塞 EventLoop），确保网关开始接收流量前缓存已 warm。
     */
    @Bean
    public HttpBizPublicKeyProvider bizPublicKeyProvider(final Environment env) {
        String source = getString(env, "gw.springcloud.key-source", "classpath");
        String classpathLocation = getString(env, "gw.springcloud.classpath-public-key", "biz-public-key.pem");
        String baseUrl = getString(env, "gw.springcloud.http.base-url", "http://127.0.0.1:8470");
        String pathPattern = getString(env, "gw.springcloud.http.path-pattern", "/sign/public-key/%s");
        int connectTimeoutMs = getInt(env, "gw.springcloud.http.connect-timeout-ms", 1000);
        int readTimeoutMs = getInt(env, "gw.springcloud.http.read-timeout-ms", 2000);
        int maxConnections = getInt(env, "gw.springcloud.http.max-connections", 20);
        long refreshIntervalSeconds = getLong(env, "gw.springcloud.http.refresh-interval-seconds", 15L);
        long cacheTtlSeconds = getLong(env, "gw.springcloud.cache.ttl-seconds", 30L);
        long retrySeconds = getLong(env, "gw.springcloud.cache.failure-retry-seconds", 3L);
        boolean allowStale = getBoolean(env, "gw.springcloud.cache.allow-stale-on-refresh-failure", true);
        HttpBizPublicKeyProvider provider = new HttpBizPublicKeyProvider(
                source, classpathLocation,
                baseUrl, pathPattern,
                connectTimeoutMs, readTimeoutMs, maxConnections, refreshIntervalSeconds,
                cacheTtlSeconds, retrySeconds, allowStale
        );
        // 预热：在 Spring 启动线程（非 EventLoop）上同步拉取已知 appKey 的公钥
        String preWarmAppKeys = getString(env, "gw.springcloud.pre-warm-app-keys", "");
        if (!preWarmAppKeys.isEmpty()) {
            String[] keys = preWarmAppKeys.split(",");
            provider.preWarm(keys);
            LOG.info("[GW-Sign] 预热完成 appKeys={}", preWarmAppKeys);
        }
        return provider;
    }

    /**
     * 自定义 SignService Bean。
     *
     * <p>此 Bean 注册后，SignPluginConfiguration 的默认 @Bean signService() 因
     * @ConditionalOnMissingBean(SignService.class) 而被跳过。
     */
    @Bean
    public SignService signService(final HttpBizPublicKeyProvider bizPublicKeyProvider) {
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
