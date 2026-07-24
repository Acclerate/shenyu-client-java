package org.apache.shenyu.plugin.sign.custom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AppAuthHealthIndicator} 单元测试。
 *
 * <p>验证就绪判据是 everSynced（非 isEmpty），避免 refresh 窗口假阴性（D2）。
 */
class AppAuthHealthIndicatorTest {

    @Test
    void downWhenNeverSynced() {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final AppAuthHealthIndicator indicator = new AppAuthHealthIndicator(provider);
        final Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("app_auth data not synced from admin yet",
                health.getDetails().get("reason"));
    }

    @Test
    void upAfterFirstSync() {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        // 模拟首次 onSubscribe，触发 everSynced=true
        provider.onSubscribe(buildAuthData("anyKey", "anySecret", true));
        final AppAuthHealthIndicator indicator = new AppAuthHealthIndicator(provider);
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void staysUpDuringRefreshWindow() {
        // D2 核心不变量：refresh 清空数据源后，HealthIndicator 仍 UP（不裸用 isEmpty）
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuthData("k1", "s1", true));
        provider.refresh();  // 清空数据源
        final AppAuthHealthIndicator indicator = new AppAuthHealthIndicator(provider);
        assertEquals(Status.UP, indicator.health().getStatus(),
                "refresh 清空窗口内应仍 UP，避免 K8s 误摘流");
    }

    private static org.apache.shenyu.common.dto.AppAuthData buildAuthData(
            final String appKey, final String appSecret, final boolean enabled) {
        return org.apache.shenyu.common.dto.AppAuthData.builder()
                .appKey(appKey)
                .appSecret(appSecret)
                .enabled(enabled)
                .build();
    }
}
