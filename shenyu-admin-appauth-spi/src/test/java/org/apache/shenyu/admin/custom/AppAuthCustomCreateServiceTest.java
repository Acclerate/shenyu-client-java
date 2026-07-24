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
 *   <li>创建分支：findByAppKey 返回 null → insertSelective + publishEvent(CREATE)</li>
 *   <li>更新分支：findByAppKey 返回已存在 → updateSelective + publishEvent(UPDATE)</li>
 *   <li>enabled/open 默认值：入参为 null 时 enabled=true, open=false</li>
 *   <li>publishEvent 的 AppAuthData 字段正确性（appKey/appSecret/enabled/open）</li>
 * </ul>
 */
class AppAuthCustomCreateServiceTest {

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
     * 创建分支：appKey 不存在 → insertSelective + publishEvent(CREATE)，响应回传 id + appKey。
     */
    @Test
    void shouldInsertAndPublishCreateEventWhenAppKeyNotExist() {
        String appKey = "YYT";
        String pem = "-----BEGIN PUBLIC KEY-----\nMIIBIjAN\n-----END PUBLIC KEY-----";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, pem, true, false);

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
        assertEquals(pem, pushed.getAppSecret());

        // 验证响应回传 id + appKey
        assertEquals(200, result.getCode());
        CustomAppAuthCreateResp data = (CustomAppAuthCreateResp) result.getData();
        assertNotNull(data.getId());
        assertEquals(appKey, data.getAppKey());
    }

    /**
     * 更新分支：appKey 已存在 → updateSelective + publishEvent(UPDATE)，复用已存在记录的 id。
     */
    @Test
    void shouldUpdateAndPublishUpdateEventWhenAppKeyExist() {
        String appKey = "SYD";
        String pem = "-----BEGIN PUBLIC KEY-----\nnewKey\n-----END PUBLIC KEY-----";
        String existId = "exist-id-123";
        AppAuthDO exist = AppAuthDO.builder().id(existId).appKey(appKey).build();
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(exist);

        CustomAppAuthCreateReq req = newReq(appKey, pem, true, false);

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
     * enabled/open 为 null 时走默认值：enabled=true, open=false。
     */
    @Test
    void shouldUseDefaultEnabledAndOpenWhenNull() {
        String appKey = "DEFAULT";
        String pem = "-----BEGIN PUBLIC KEY-----\nx\n-----END PUBLIC KEY-----";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, pem, null, null);

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
        String pem = "-----BEGIN PUBLIC KEY-----\ny\n-----END PUBLIC KEY-----";
        when(appAuthMapper.findByAppKey(appKey)).thenReturn(null);

        CustomAppAuthCreateReq req = newReq(appKey, pem, false, null);

        service.upsertAndPush(req);

        ArgumentCaptor<DataChangedEvent> captor = ArgumentCaptor.forClass(DataChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AppAuthData pushed = (AppAuthData) captor.getValue().getSource().get(0);
        assertEquals(Boolean.FALSE, pushed.getEnabled());
        assertEquals(Boolean.FALSE, pushed.getOpen());
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
