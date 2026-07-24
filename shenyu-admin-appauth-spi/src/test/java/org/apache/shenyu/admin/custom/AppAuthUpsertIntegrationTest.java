/*
 * AppAuthCustomCreateService 落库集成测试（真实 MySQL）。
 *
 * <p>不依赖 H2：直接连本地 MySQL（docker-compose 的 mysql57，库 shenyu_261，root/root）。
 * 跑测试前自动对 app_auth（及配置的 pay_app_config）做「备份→清空」，跑完再「还原」，
 * 因此不会永久丢失本地数据（"清空但保留数据"）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>创建：upsert 指定 appKey + PEM → 落库 1 行，app_secret=PEM，enabled/open 取默认 true/false；</li>
 *   <li>同 appKey 二次调用 = 更新公钥（id 不变、appKey 不可变、app_secret 换新）；</li>
 *   <li>非法 PEM → 400，不落库、不推送；</li>
 *   <li>PATCH 语义：更新时不传 enabled/open → 保留现值（disabled 不被静默重新启用）；</li>
 *   <li>默认标志：insert 时 enabled/open 为 null → enabled=true/open=false。</li>
 * </ul>
 *
 * <p>连接配置：读取 {@code src/test/resources/datasource.properties}；缺失则用默认值
 * （localhost:3306/shenyu_261, root/root）。若 MySQL 不可用，整个测试类被 skip（不报错）。
 */
package org.apache.shenyu.admin.custom;

import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateReq;
import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateResp;
import org.apache.shenyu.admin.listener.DataChangedEvent;
import org.apache.shenyu.admin.mapper.AppAuthMapper;
import org.apache.shenyu.admin.model.entity.AppAuthDO;
import org.apache.shenyu.admin.model.result.ShenyuAdminResult;
import org.apache.shenyu.common.enums.ConfigGroupEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * app_auth 落库 + 公钥更新 集成测试（真实 MySQL）。
 */
class AppAuthUpsertIntegrationTest {

    private static JdbcTemplate jdbcTemplate;
    private static AppAuthCustomCreateService service;
    private static JdbcAppAuthMapper mapper;
    private static RecordingEventPublisher publisher;
    private static Map<String, String> backups;
    private static boolean connected = false;

    private static final List<String> CLEAR_TABLES = Arrays.asList("app_auth", "pay_app_config");

    private static String pemA;
    private static String pemB;
    private static String pemC;

    @BeforeAll
    static void setUp() throws Exception {
        final Properties props = loadProperties();
        final String url = props.getProperty("jdbc.url",
                "jdbc:mysql://localhost:3306/shenyu_261?useUnicode=true&characterEncoding=UTF-8"
                        + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        final String user = props.getProperty("jdbc.username", "root");
        final String password = props.getProperty("jdbc.password", "root");

        final DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        jdbcTemplate = new JdbcTemplate(ds);

        // 连接自检：连不上则整类 skip（不报错，不碰任何数据）
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            connected = true;
        } catch (final Exception e) {
            connected = false;
            System.err.println("[AppAuthUpsertIntegrationTest] MySQL 连接自检失败: " + e);
            e.printStackTrace();
        }
        Assumptions.assumeTrue(connected,
                "本地 MySQL 不可用（" + url + "），跳过 app_auth 落库集成测试（不会修改任何数据）");

        // 备份并清空（保留数据）
        backups = DbBackupRestore.backupAndClear(jdbcTemplate,
                props.containsKey("tables")
                        ? Arrays.asList(props.getProperty("tables").split(","))
                        : CLEAR_TABLES);

        final AppAuthMapper appAuthMapper = new JdbcAppAuthMapper(jdbcTemplate);
        mapper = (JdbcAppAuthMapper) appAuthMapper;
        publisher = new RecordingEventPublisher();
        service = new AppAuthCustomCreateService(appAuthMapper, publisher);

        // 预生成三套合法 RSA 公钥 PEM（用于轮换 / 不同 appKey）
        pemA = toPem(genKeyPair().getPublic());
        pemB = toPem(genKeyPair().getPublic());
        pemC = toPem(genKeyPair().getPublic());
    }

    @AfterAll
    static void tearDown() {
        if (connected && backups != null && !backups.isEmpty()) {
            DbBackupRestore.restoreAndDrop(jdbcTemplate, backups);
        }
    }

    /**
     * 创建 + 同 appKey 轮换公钥：落库 1 行、id 稳定、app_secret 换新、推送 APP_AUTH UPDATE。
     */
    @Test
    void createThenRotatePublicKeyKeepsIdAndSwapsSecret() {
        final String appKey = "IT-ROTATE";
        publisher.clear();

        // 创建
        ShenyuAdminResult r1 = service.upsertAndPush(newReq(appKey, pemA, true, false));
        assertEquals(200, r1.getCode());
        final String id = ((CustomAppAuthCreateResp) r1.getData()).getId();
        assertNotNull(id);

        AppAuthDO row1 = mapper.findByAppKey(appKey);
        assertNotNull(row1, "创建后应落库");
        assertEquals(1, countRows(appKey), "app_auth 应仅 1 行");
        assertEquals(pemA, row1.getAppSecret());
        assertTrue(row1.getEnabled());
        assertFalse(row1.getOpen());

        // 同 appKey 轮换公钥（仅换 appSecret，enabled/open 不传 → 保留现值）
        publisher.clear();
        ShenyuAdminResult r2 = service.upsertAndPush(newReq(appKey, pemB, null, null));
        assertEquals(200, r2.getCode());
        assertEquals(id, ((CustomAppAuthCreateResp) r2.getData()).getId(), "更新分支应复用同一 id");

        AppAuthDO row2 = mapper.findByAppKey(appKey);
        assertEquals(1, countRows(appKey), "轮换后仍应仅 1 行（更新而非新增）");
        assertEquals(pemB, row2.getAppSecret(), "app_secret 应已轮换为新公钥");
        assertEquals(id, row2.getId(), "id 不应变（appKey 不可变）");
        assertTrue(row2.getEnabled(), "未传 enabled → 保留现值 true");
        assertFalse(row2.getOpen(), "未传 open → 保留现值 false");

        assertAppAuthEventPublished(ConfigGroupEnum.APP_AUTH);
    }

    /**
     * 非法 PEM → 400，不落库、不推送。
     */
    @Test
    void invalidPemReturns400AndNoWrite() {
        final String appKey = "IT-BAD-PEM";
        publisher.clear();

        ShenyuAdminResult result = service.upsertAndPush(newReq(appKey, "not-a-valid-pem", null, null));
        assertEquals(400, result.getCode());
        assertNull(mapper.findByAppKey(appKey), "非法 PEM 不应落库");
        assertTrue(publisher.getEvents().isEmpty(), "非法 PEM 不应推送");
    }

    /**
     * 已禁用 appKey 只轮换公钥 → DB 与推送均保留 enabled=false（不被静默重新启用）。
     */
    @Test
    void disabledPreservedWhenRotatingOnly() {
        final String appKey = "IT-DISABLED";
        publisher.clear();

        // 先创建为 disabled
        service.upsertAndPush(newReq(appKey, pemA, false, true));
        AppAuthDO created = mapper.findByAppKey(appKey);
        assertFalse(created.getEnabled(), "初始应为 disabled");

        // 只轮换公钥，enabled/open 不传
        publisher.clear();
        service.upsertAndPush(newReq(appKey, pemC, null, null));
        AppAuthDO after = mapper.findByAppKey(appKey);
        assertEquals(pemC, after.getAppSecret(), "公钥应已轮换");
        assertFalse(after.getEnabled(), "轮换后仍应为 disabled（保留现值）");
        assertTrue(after.getOpen(), "open 应仍为 true（保留现值）");

        // 推送的 AppAuthData 也保留 disabled
        DataChangedEvent event = lastAppAuthEvent();
        org.apache.shenyu.common.dto.AppAuthData pushed =
                (org.apache.shenyu.common.dto.AppAuthData) event.getSource().get(0);
        assertFalse(pushed.getEnabled(), "推送到网关的 enabled 应为 false");
    }

    /**
     * insert 分支 enabled/open 为 null → 默认 enabled=true/open=false。
     */
    @Test
    void defaultFlagsWhenNullOnInsert() {
        final String appKey = "IT-DEFAULT";
        service.upsertAndPush(newReq(appKey, pemA, null, null));
        AppAuthDO row = mapper.findByAppKey(appKey);
        assertNotNull(row);
        assertTrue(row.getEnabled(), "默认 enabled 应为 true");
        assertFalse(row.getOpen(), "默认 open 应为 false");
    }

    // ----------------- 工具方法 -----------------

    private static int countRows(final String appKey) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_auth WHERE app_key = ?", Integer.class, appKey);
        return n == null ? 0 : n;
    }

    private static void assertAppAuthEventPublished(final ConfigGroupEnum expected) {
        DataChangedEvent event = lastAppAuthEvent();
        assertNotNull(event, "应发布 APP_AUTH 事件");
        assertEquals(expected, event.getGroupKey());
    }

    private static DataChangedEvent lastAppAuthEvent() {
        for (int i = publisher.getEvents().size() - 1; i >= 0; i--) {
            final Object e = publisher.getEvents().get(i);
            if (e instanceof DataChangedEvent) {
                return (DataChangedEvent) e;
            }
        }
        return null;
    }

    private static CustomAppAuthCreateReq newReq(final String appKey, final String appSecret,
                                                final Boolean enabled, final Boolean open) {
        final CustomAppAuthCreateReq req = new CustomAppAuthCreateReq();
        req.setAppKey(appKey);
        req.setAppSecret(appSecret);
        req.setEnabled(enabled);
        req.setOpen(open);
        return req;
    }

    private static KeyPair genKeyPair() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static String toPem(final PublicKey publicKey) {
        final byte[] der = publicKey.getEncoded();
        final String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    private static Properties loadProperties() throws Exception {
        final Properties props = new Properties();
        final Path path = Path.of("src/test/resources/datasource.properties");
        if (Files.exists(path)) {
            try (java.io.InputStream in = Files.newInputStream(path)) {
                props.load(in);
            }
        }
        return props;
    }
}
