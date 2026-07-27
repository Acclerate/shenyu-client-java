package org.apache.shenyu.admin.custom;

import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateReq;
import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateResp;
import org.apache.shenyu.admin.listener.DataChangedEvent;
import org.apache.shenyu.admin.mapper.AppAuthMapper;
import org.apache.shenyu.admin.model.entity.AppAuthDO;
import org.apache.shenyu.admin.model.result.ShenyuAdminResult;
import org.apache.shenyu.common.dto.AppAuthData;
import org.apache.shenyu.common.enums.ConfigGroupEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppAuthCustomCreateService 单元测试。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>创建分支：findByAppKey 返回 null → insertSelective + publishEvent(APP_AUTH, CREATE)</li>
 *   <li>更新分支：findByAppKey 返回已存在 → updateSelective + publishEvent(APP_AUTH, UPDATE)</li>
 *   <li>insert 缺省：enabled/open 入参 null 且无现值 → enabled=true, open=false</li>
 *   <li>enabled=false 显式禁用被尊重</li>
 *   <li>P1① 回归：update 未传 enabled/open → 保留 exist 现值（禁用的 appKey 轮换公钥不被静默重新启用）</li>
 *   <li>P1① 回归：update 显式传 enabled=true → 覆盖 exist 的 false（显式优先于现值）</li>
 *   <li>P2⑤ 回归：非法公钥 → 400，不落库、不推送</li>
 * </ul>
 */
class AppAuthCustomCreateServiceTest {

    /**
     * 真实的 2048-bit X.509 RSA 公钥裸 Base64（openssl 生成后剥离 PEM 头尾，仅测试用），可通过 fail-fast 校验。
     */
    private static final String VALID_PEM = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4XNGWw1qa9v9Q4BbQlWz\n"
            + "RgiG+Fnxa46oFPkliqSOF6QSlZWmiReWwFPVJ7kHLb1hZndmbjSYdt7ZBhKtrA9Q\n"
            + "XsuustaqObqWGa++4dDAAG5VOhF0Xo/WUUk9PMMa8ouMyfO6pG4qozynAHo7ZnTP\n"
            + "2PvnFDA69ctYsuA6HSPP/08qe2qUXBjvgshSRA07yOwe2IOhmkNlatJaRn0vpmId\n"
            + "BiXyotFBXcK6z3of8Y7yfbh4PrMGvni3bnHu45+7kxX/VmrHDRfaSaw1M2hcsFdx\n"
            + "RPhu7JzghK36Sbp5kzqw148GfbWYQNGyH5Ps6e8SXhaLDfWTkNiyitR0LuV5lJm3\n"
            + "swIDAQAB";

    private AppAuthMapper appAuthMapper;
    private ApplicationEventPublisher eventPublisher;
    private AppAuthCustomCreateService service;

    @BeforeEach
    void setUp() {
        appAuthMapper = mock(AppAuthMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AppAuthCustomCreateService(appAuthMapper, eventPublisher);
    }

    /**
     * 创建分支：appKey 不存在 → insertSelective + publishEvent(APP_AUTH, CREATE)，响应回传 id + appKey。
     */
    @Test
    void shouldInsertAndPublishCreateEventWhenAppKeyNotExist() {
        String appKey = "YYT";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, true, false);

        ShenyuAdminResult result = service.upsertAndPush(req);

        // 验证 insertSelective 被调用（创建分支），updateSelective 未调用
        verify(appAuthMapper, times(1)).insertSelective(any(AppAuthDO.class));
        verify(appAuthMapper, never()).updateSelective(any(AppAuthDO.class));

        // 验证 publishEvent 被调用，group=APP_AUTH
        ArgumentCaptor<DataChangedEvent> captor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        DataChangedEvent event = captor.getValue();
        assertEquals(ConfigGroupEnum.APP_AUTH, event.getGroupKey());
        // 验证推送的 AppAuthData 字段
        AppAuthData pushed = (AppAuthData) event.getSource().get(0);
        assertEquals(appKey, pushed.getAppKey());
        assertEquals(VALID_PEM, pushed.getAppSecret());

        // 验证响应回传 id + appKey
        assertEquals(200, result.getCode());
        CustomAppAuthCreateResp data = (CustomAppAuthCreateResp) result.getData();
        assertNotNull(data.getId());
        assertEquals(appKey, data.getAppKey());
    }

    /**
     * 更新分支：appKey 已存在 → updateSelective + publishEvent(APP_AUTH, UPDATE)，复用已存在记录的 id。
     */
    @Test
    void shouldUpdateAndPublishUpdateEventWhenAppKeyExist() {
        String appKey = "SYD";
        String existId = "exist-id-123";
        AppAuthDO exist = AppAuthDO.builder().id(existId).appKey(appKey).enabled(true).open(false).build();
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(exist);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, true, false);

        ShenyuAdminResult result = service.upsertAndPush(req);

        // 验证 updateSelective 被调用（更新分支），insertSelective 未调用
        verify(appAuthMapper, times(1)).updateSelective(any(AppAuthDO.class));
        verify(appAuthMapper, never()).insertSelective(any(AppAuthDO.class));

        // 验证 publishEvent 被调用，group=APP_AUTH
        ArgumentCaptor<DataChangedEvent> captor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        DataChangedEvent event = captor.getValue();
        assertEquals(ConfigGroupEnum.APP_AUTH, event.getGroupKey());

        // 验证响应回传的 id 是已存在记录的 id（复用）
        CustomAppAuthCreateResp data = (CustomAppAuthCreateResp) result.getData();
        assertEquals(existId, data.getId());
        assertEquals(appKey, data.getAppKey());
    }

    /**
     * insert 分支缺省：enabled/open 为 null 且无现值 → enabled=true, open=false。
     */
    @Test
    void shouldUseDefaultEnabledAndOpenWhenNullOnInsert() {
        String appKey = "DEFAULT";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, null, null);

        service.upsertAndPush(req);

        // 验证推送的 AppAuthData 用了默认值
        ArgumentCaptor<DataChangedEvent> captor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AppAuthData pushed = (AppAuthData) captor.getValue().getSource().get(0);
        assertEquals(Boolean.TRUE, pushed.getEnabled());
        assertEquals(Boolean.FALSE, pushed.getOpen());
    }

    /**
     * enabled=false 时推送的 AppAuthData.enabled=false（验签会拒签，符合语义）。
     */
    @Test
    void shouldRespectDisabledFlagWhenEnabledFalse() {
        String appKey = "DISABLED";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, false, null);

        service.upsertAndPush(req);

        ArgumentCaptor<DataChangedEvent> captor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AppAuthData pushed = (AppAuthData) captor.getValue().getSource().get(0);
        assertEquals(Boolean.FALSE, pushed.getEnabled());
        assertEquals(Boolean.FALSE, pushed.getOpen());
    }

    /**
     * P1① 回归（核心场景）：已禁用的 appKey 只轮换公钥（不传 enabled/open）
     * → DB 写入与推送均保留 enabled=false / open=true 现值，不被静默重新启用。
     */
    @Test
    void shouldKeepExistingEnabledAndOpenWhenNotProvidedOnUpdate() {
        String appKey = "ROTATE-ONLY";
        AppAuthDO exist = AppAuthDO.builder()
                .id("id-1").appKey(appKey).enabled(false).open(true).build();
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(exist);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, null, null);

        service.upsertAndPush(req);

        // DB 写入保留现值
        ArgumentCaptor<AppAuthDO> doCaptor = ArgumentCaptor.forClass(AppAuthDO.class);
        verify(appAuthMapper).updateSelective(doCaptor.capture());
        assertEquals(Boolean.FALSE, doCaptor.getValue().getEnabled());
        assertEquals(Boolean.TRUE, doCaptor.getValue().getOpen());

        // 推送到网关的 AppAuthData 同样保留现值（网关 enabled 检查依赖它）
        ArgumentCaptor<DataChangedEvent> evCaptor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        AppAuthData pushed = (AppAuthData) evCaptor.getValue().getSource().get(0);
        assertEquals(Boolean.FALSE, pushed.getEnabled());
        assertEquals(Boolean.TRUE, pushed.getOpen());
    }

    /**
     * P1① 补充：update 显式传 enabled=true → 覆盖 exist 的 false（显式意图优先于现值）。
     */
    @Test
    void shouldOverrideExistingEnabledWhenExplicitlyProvidedOnUpdate() {
        String appKey = "RE-ENABLE";
        AppAuthDO exist = AppAuthDO.builder()
                .id("id-2").appKey(appKey).enabled(false).open(false).build();
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(exist);

        CustomAppAuthCreateReq req = newReq(appKey, VALID_PEM, true, null);

        service.upsertAndPush(req);

        ArgumentCaptor<AppAuthDO> doCaptor = ArgumentCaptor.forClass(AppAuthDO.class);
        verify(appAuthMapper).updateSelective(doCaptor.capture());
        assertEquals(Boolean.TRUE, doCaptor.getValue().getEnabled());
        // open 未传 → 保留现值 false
        assertEquals(Boolean.FALSE, doCaptor.getValue().getOpen());
    }

    /**
     * P2⑤ 回归：带 PEM 头尾标记的串（新契约只认裸 Base64）→ 400，不落库、不推送。
     */
    @Test
    void shouldRejectInvalidPemWithoutDbWriteOrPush() {
        String appKey = "BAD-PEM";
        // 新契约下 PemUtils 不再剥标记，含 ----- 和字母的串无法通过 Base64 解码
        String badPem = "-----BEGIN PUBLIC KEY-----\nnot-a-real-key\n-----END PUBLIC KEY-----";

        CustomAppAuthCreateReq req = newReq(appKey, badPem, null, null);

        ShenyuAdminResult result = service.upsertAndPush(req);

        assertEquals(400, result.getCode());
        verify(appAuthMapper, never()).insertSelective(any(AppAuthDO.class));
        verify(appAuthMapper, never()).updateSelective(any(AppAuthDO.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * P2⑤ 回归：非法裸串（非合法 Base64）→ 400（被 Base64 解码 / KeyFactory 拒绝）。
     */
    @Test
    void shouldRejectGarbageSecretWithoutMarkers() {
        String appKey = "GARBAGE";

        CustomAppAuthCreateReq req = newReq(appKey, "hello-world-not-pem", null, null);

        ShenyuAdminResult result = service.upsertAndPush(req);

        assertEquals(400, result.getCode());
        verify(appAuthMapper, never()).insertSelective(any(AppAuthDO.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private CustomAppAuthCreateReq newReq(String appKey, String appSecret, Boolean enabled, Boolean open) {
        CustomAppAuthCreateReq req = new CustomAppAuthCreateReq();
        req.setAppKey(appKey);
        req.setAppSecret(appSecret);
        req.setEnabled(enabled);
        req.setOpen(open);
        return req;
    }
}
