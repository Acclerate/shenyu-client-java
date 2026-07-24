/*
 * AppAuthHealthIndicator —— app_auth 数据同步就绪检查。
 *
 * 暴露给 Spring Boot Actuator 的 /actuator/health，配合 K8s readinessProbe 使用：
 * 网关启动后 websocket 全量同步完成前，本指标返回 DOWN，K8s 不把流量打过来；
 * 首次同步完成后返回 UP，开始接收流量。
 *
 * 判据（D2）：用 SignCacheBizPublicKeyProvider.isEverSynced()，而非 isCacheEmpty()。
 * 原因：全量 REFRESH 时 Provider 会清空数据源逐条重填，此清空窗口内 isCacheEmpty()=true，
 *   若用它做判据会导致 HealthIndicator 误报 DOWN，K8s 可能误摘流。everSynced 一旦 true
 *   永不变 false，避免 refresh 窗口的假阴性。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * app_auth 数据同步就绪健康指标。
 *
 * <p>不使用 {@code @Component}（bootstrap 主类在 {@code org.apache.shenyu.bootstrap}，
 * 扫不到本包），由 {@link PayRsaSignConfiguration} 以 {@code @Bean} 显式注册（铁律 2）。
 */
public final class AppAuthHealthIndicator implements HealthIndicator {

    private final SignCacheBizPublicKeyProvider provider;

    public AppAuthHealthIndicator(final SignCacheBizPublicKeyProvider provider) {
        this.provider = provider;
    }

    @Override
    public Health health() {
        if (provider.isEverSynced()) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("reason", "app_auth data not synced from admin yet")
                .build();
    }
}
