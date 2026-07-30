package org.apache.shenyu.plugin.sign.custom;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PayReplayGuard 真实 Redis 集成测试。
 *
 * <p>环境：本机 docker redis（docker-compose-redis.yml，localhost:6379，密码 123456）。
 * 连不上整类 skip（真实 Redis 集成测试通用模式）。
 */
class PayReplayGuardRealRedisTest {

    private static final String REDIS_URI = System.getenv().getOrDefault(
            "PAY_REPLAY_TEST_REDIS_URI", "redis://:123456@localhost:6379/0");

    private static final String KEY = "replay:test:IT-TS:IT-NONCE";

    private static RedisClient probeClient;

    private static StatefulRedisConnection<String, String> probe;

    private static PayReplayGuard guard;

    @BeforeAll
    static void connectOrSkip() {
        try {
            probeClient = RedisClient.create(RedisURI.create(REDIS_URI));
            probe = probeClient.connect();
            probe.setTimeout(Duration.ofMillis(1000));
            assumeTrue("PONG".equals(probe.sync().ping()), "redis 无响应，跳过集成测试");
        } catch (final Throwable ex) {
            // Exception=连不上；Error（如 netty 版本冲突 NoSuchFieldError）=本地环境不满足，同样跳过
            assumeTrue(false, "redis 环境不可用（" + ex + "），跳过集成测试");
        }
        guard = new PayReplayGuard(PayReplayProperties.of(
                true, REDIS_URI, 330, 1000L, true, 30_000L));
    }

    @AfterEach
    void cleanKey() {
        if (probe != null) {
            probe.sync().del(KEY);
        }
    }

    @AfterAll
    static void tearDown() {
        if (guard != null) {
            guard.close();
        }
        if (probe != null) {
            probe.close();
        }
        if (probeClient != null) {
            probeClient.shutdown();
        }
    }

    @Test
    void freshNonceMarksAndBlocksReplay() {
        assertTrue(guard.tryMark(KEY), "首次 nonce 应标记成功（放行）");
        assertFalse(guard.tryMark(KEY), "相同 (appKey,timestamp,nonce) 重放应被拦截");
        // Value 契约
        assertTrue(PayReplayGuard.REPLAY_VALUE.equals(probe.sync().get(KEY)));
    }

    @Test
    void ttlIsFiveMinutesPlusThirtySeconds() {
        assertTrue(guard.tryMark(KEY));
        final long ttl = probe.sync().ttl(KEY);
        // 固定 330s（允许测试耗时损耗 2s）
        assertTrue(ttl >= 328 && ttl <= 330, "TTL 应≈330s，实际=" + ttl);
    }

    @Test
    void delAllowsReacquire() {
        assertTrue(guard.tryMark(KEY));
        probe.sync().del(KEY);
        assertTrue(guard.tryMark(KEY), "DEL 后应可再次标记（模拟 TTL 过期后重放窗口外）");
    }
}
