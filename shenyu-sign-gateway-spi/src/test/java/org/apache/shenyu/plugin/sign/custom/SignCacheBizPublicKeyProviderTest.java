package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.common.dto.AppAuthData;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SignCacheBizPublicKeyProvider} 单元测试。
 *
 * <p>覆盖：L2 命中/miss、enabled 实时查（D3）、就绪语义（everSynced）、
 * subscriber 回调、refresh 清空、并发 miss 幂等、PEM 非法不污染缓存。
 */
class SignCacheBizPublicKeyProviderTest {

    /** 生成 RSA-2048 公钥的标准 PEM */
    private static String generatePem() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return toPem(kpg.generateKeyPair().getPublic());
    }

    private static String toPem(final PublicKey publicKey) {
        final byte[] der = publicKey.getEncoded();
        final String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    private static AppAuthData buildAuth(final String appKey, final String pem, final Boolean enabled) {
        return AppAuthData.builder()
                .appKey(appKey)
                .appSecret(pem)
                .enabled(enabled)
                .build();
    }

    // ---------------- currentKey 基本路径 ----------------

    @Test
    void currentKeyMissThenParseAndCache() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, true));

        final PublicKey key = provider.currentKey("k1");
        assertNotNull(key);
        assertEquals("RSA", key.getAlgorithm());
        assertFalse(provider.isCacheEmpty());
    }

    @Test
    void currentKeyHitReturnsCachedWithoutReparse() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, true));

        final PublicKey first = provider.currentKey("k1");
        final PublicKey second = provider.currentKey("k1");
        // 同一对象引用 = 命中 L2，未重新 parse
        assertEquals(System.identityHashCode(first), System.identityHashCode(second));
    }

    // ---------------- enabled 实时查（D3） ----------------

    @Test
    void disabledThrowsEvenIfL2Hit() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, true));
        provider.currentKey("k1");  // 预热 L2

        // 切换 enabled=false（D3：实时查，不缓存 enabled 进 L2）
        provider.onSubscribe(buildAuth("k1", pem, false));
        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void enabledNullDoesNotNpe() throws Exception {
        // 铁律 5：enabled 为 null 时不应 NPE，应按"非 true"处理（disabled 语义）
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, null));
        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("disabled"), "enabled=null 应视为 disabled");
    }

    @Test
    void l2HitButSourceEvictedReturnsNotFoundNotDisabled() throws Exception {
        // 修正 P2 语义 bug：L2 有公钥但数据源已无此 key（被 unSubscribe 删除），应走 not found 非 disabled
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, true));
        provider.currentKey("k1");  // 预热 L2

        provider.unSubscribe(buildAuth("k1", pem, true));  // 数据源删除，L2 也被 evict
        // 此时 L2 也空了，走 miss → 数据源 null → not found
        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("no app_auth record"));
    }

    // ---------------- 就绪语义（everSynced） ----------------

    @Test
    void notReadyBeforeFirstSync() {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        assertFalse(provider.isEverSynced());
        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("not initialized yet"),
                "未就绪时应报 not initialized，而非 not found");
    }

    @Test
    void notFoundAfterSyncedButKeyAbsent() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuth("other", generatePem(), true));  // 触发 everSynced
        assertTrue(provider.isEverSynced());

        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("synced but not found"));
    }

    @Test
    void everSyncedStaysTrueAfterRefresh() throws Exception {
        // D2 核心不变量：refresh 清空数据源，everSynced 仍 true
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuth("k1", generatePem(), true));
        assertTrue(provider.isEverSynced());
        provider.refresh();
        assertTrue(provider.isEverSynced(), "refresh 不应重置 everSynced");
        assertTrue(provider.isCacheEmpty(), "refresh 应清空数据源");
    }

    // ---------------- subscriber 回调 ----------------

    @Test
    void onSubscribeEvictsL2SoNextAccessReparse() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem1 = generatePem();
        provider.onSubscribe(buildAuth("k1", pem1, true));
        final PublicKey first = provider.currentKey("k1");

        // 推送新 PEM（公钥轮换）
        final String pem2 = generatePem();
        provider.onSubscribe(buildAuth("k1", pem2, true));
        final PublicKey second = provider.currentKey("k1");

        assertNotEqualsIdentity(first, second, "onSubscribe 应 evict L2，下次重新 parse");
    }

    @Test
    void unSubscribeRemovesFromSourceAndL2() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuth("k1", generatePem(), true));
        provider.currentKey("k1");
        assertFalse(provider.isCacheEmpty());

        provider.unSubscribe(buildAuth("k1", null, true));
        // 数据源与 L2 都应无 k1
        final Exception ex = assertThrows(Exception.class, () -> provider.currentKey("k1"));
        assertTrue(ex.getMessage().contains("no app_auth record"));
    }

    @Test
    void onSubscribeIgnoreNullOrBlankAppKey() {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuth(null, "s", true));
        provider.onSubscribe(buildAuth("  ", "s", true));
        assertFalse(provider.isEverSynced(), "非法 appKey 不应触发 everSynced");
    }

    // ---------------- 并发 miss 幂等 ----------------

    @Test
    void concurrentMissSameAppKeyConverges() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        final String pem = generatePem();
        provider.onSubscribe(buildAuth("k1", pem, true));

        final int threads = 20;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    final PublicKey key = provider.currentKey("k1");
                    assertNotNull(key);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发应在 10s 内完成");
        pool.shutdown();
        if (error.get() != null) {
            throw new AssertionError("并发 miss 出错", error.get());
        }
    }

    // ---------------- PEM 非法不污染缓存 ----------------

    @Test
    void invalidPemDoesNotPolluteL2() throws Exception {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        provider.onSubscribe(buildAuth("k1", "not-a-valid-pem", true));
        assertThrows(Exception.class, () -> provider.currentKey("k1"));

        // 推送合法 PEM 后应能正常解析（说明非法那次没污染 L2）
        final String validPem = generatePem();
        provider.onSubscribe(buildAuth("k1", validPem, true));
        assertNotNull(provider.currentKey("k1"));
    }

    // ---------------- 边界 ----------------

    @Test
    void blankAppKeyThrowsIllegalArgument() {
        final SignCacheBizPublicKeyProvider provider = new SignCacheBizPublicKeyProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.currentKey(""));
        assertThrows(IllegalArgumentException.class, () -> provider.currentKey("  "));
        assertThrows(IllegalArgumentException.class, () -> provider.currentKey(null));
    }

    // ---------------- 辅助 ----------------

    private static String generatePemQuietly() {
        try {
            return generatePem();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertNotEqualsIdentity(final PublicKey a, final PublicKey b, final String msg) {
        assertFalse(System.identityHashCode(a) == System.identityHashCode(b), msg);
    }
}
