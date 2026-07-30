package org.apache.shenyu.plugin.sign.custom;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PayReplayGuard 单测：首次标记/重放拦截/fail-open/熔断打开与半开恢复。
 *
 * <p>通过覆写 {@link PayReplayGuard#commands()} 注入 mock RedisCommands，不依赖真实 Redis。
 */
class PayReplayGuardTest {

    private static final String KEY = "replay:06:1750000000000:abc123def456";

    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);

    /** 阈值 3、冷却 200ms，便于测熔断 */
    private PayReplayGuard guard;

    @BeforeEach
    void setUp() {
        final PayReplayProperties props = PayReplayProperties.of(
                true, "redis://ignored:6379", 330, 500L, true, 200L);
        guard = new PayReplayGuard(props) {
            @Override
            protected RedisCommands<String, String> commands() {
                return commands;
            }
        };
    }

    @Test
    void disabledAlwaysMarks() {
        final PayReplayProperties off = PayReplayProperties.of(
                false, "", 330, 500L, true, 200L);
        final PayReplayGuard disabled = new PayReplayGuard(off) {
            @Override
            protected RedisCommands<String, String> commands() {
                throw new AssertionError("disabled 时不应触达 Redis");
            }
        };
        assertTrue(disabled.tryMark(KEY));
    }

    @Test
    void markSuccessWhenSetNxOk() {
        when(commands.set(eq(KEY), eq(PayReplayGuard.REPLAY_VALUE), any(SetArgs.class))).thenReturn("OK");
        assertTrue(guard.tryMark(KEY));
    }

    @Test
    void replayDetectedWhenKeyExists() {
        // SET NX 未生效时 lettuce 返回 null（key 已存在）
        when(commands.set(eq(KEY), anyString(), any(SetArgs.class))).thenReturn(null);
        assertFalse(guard.tryMark(KEY));
    }

    @Test
    void failOpenOnRedisException() {
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenThrow(new IllegalStateException("connection refused"));
        // Redis 异常 → fail-open 放行
        assertTrue(guard.tryMark(KEY));
    }

    @Test
    void breakerOpensAfterConsecutiveFailuresAndSkipsRedis() {
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenThrow(new IllegalStateException("down"));
        // 连续 3 次失败（=阈值）→ 熔断打开
        for (int i = 0; i < 3; i++) {
            assertTrue(guard.tryMark(KEY));
        }
        reset(commands);
        // 熔断打开期间：直接放行，不再触达 Redis
        assertTrue(guard.tryMark(KEY));
        verify(commands, never()).set(anyString(), anyString(), any(SetArgs.class));
    }

    @Test
    void breakerHalfOpenThenRecovers() throws InterruptedException {
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
                .thenThrow(new IllegalStateException("down"));
        for (int i = 0; i < 3; i++) {
            guard.tryMark(KEY);
        }
        // 冷却期 200ms 结束
        Thread.sleep(250L);
        reset(commands);
        when(commands.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");
        // 半开探测：该请求真实触达 Redis 且成功 → 熔断关闭
        assertTrue(guard.tryMark(KEY));
        verify(commands, times(1)).set(anyString(), anyString(), any(SetArgs.class));
        // 熔断已关闭：后续请求继续走 Redis
        assertTrue(guard.tryMark(KEY));
        verify(commands, times(2)).set(anyString(), anyString(), any(SetArgs.class));
    }
}
