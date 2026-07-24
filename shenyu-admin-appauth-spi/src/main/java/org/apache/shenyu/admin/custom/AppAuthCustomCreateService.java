package org.apache.shenyu.admin.custom;

import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateReq;
import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shenyu.admin.mapper.AppAuthMapper;
import org.apache.shenyu.admin.model.entity.AppAuthDO;
import org.apache.shenyu.admin.model.result.ShenyuAdminResult;
import org.apache.shenyu.common.dto.AppAuthData;
import org.apache.shenyu.common.enums.ConfigGroupEnum;
import org.apache.shenyu.common.enums.DataEventTypeEnum;
import org.apache.shenyu.common.utils.UUIDUtils;
import org.apache.shenyu.admin.listener.DataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.sql.Timestamp;
import java.util.Collections;

/**
 * app_auth 自定义 upsert + websocket 推送服务。
 *
 * <p>核心职责：用入参 appKey + appSecret(PEM) 在 app_auth 表 upsert 一条记录，并手动
 * publishEvent(DataChangedEvent) 触发 admin 的 WebsocketDataChangedListener 把 AppAuthData
 * 推送到所有 bootstrap 的 SignAuthDataCache（设计方案 v2.0 实时公钥源）。
 *
 * <p><b>为什么不复用原生 AppAuthService.createOrUpdate</b>：
 * createOrUpdate 的 id 为空分支会 setAppSecret(SignUtils.generateKey()) 随机覆盖 appSecret
 * （AppAuthServiceImpl L265），导致调用方传入的 PEM 公钥丢失。本服务自控全字段，规避该覆盖。
 *
 * <p><b>为什么不裸调 Mapper 不发事件</b>：裸 insertSelective/updateSelective 只落库，
 * 网关 SignAuthDataCache 不会收到推送（docs 反复强调「禁止直改 app_auth 表」就是这个原因）。
 * 本服务在落库后手动 publishEvent，与原生 updateDetail/createOrUpdate 的推送语义一致。
 *
 * <p>事务边界：本方法不加 @Transactional。insert/update 单条操作 + publishEvent，
 * 即便 publishEvent 失败也只是网关延迟同步（admin 可手动 syncData 兜底），不破坏 DB 一致性。
 * 若未来需要强一致，可加 @Transactional(rollbackFor=Exception.class)。
 */
@Slf4j
@RequiredArgsConstructor
public class AppAuthCustomCreateService {

    private final AppAuthMapper appAuthMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * upsert 并推送。
     *
     * <p>流程：
     * <ol>
     *   <li>findByAppKey 查是否已存在（appKey 在 app_auth 表语义上唯一）</li>
     *   <li>不存在 → insertSelective 创建（完全自控字段：appKey/appSecret/enabled/open/userId/id）</li>
     *   <li>已存在 → updateSelective 更新（SET 子句含 appSecret/enabled/open，不含 app_key——appKey 不可变，符合需求）</li>
     *   <li>publishEvent(APP_AUTH, CREATE/UPDATE) 触发 websocket 推送</li>
     * </ol>
     *
     * @param req 请求体（appKey/appSecret 必填，enabled/open 可空走默认）
     * @return ShenyuAdminResult，data 为 CustomAppAuthCreateResp（含 id + appKey）
     */
    public ShenyuAdminResult upsertAndPush(CustomAppAuthCreateReq req) {
        String appKey = req.getAppKey();
        String appSecret = req.getAppSecret();
        boolean enabled = req.getEnabled() == null || req.getEnabled();
        boolean open = req.getOpen() != null && req.getOpen();

        AppAuthDO exist = appAuthMapper.findByAppKey(appKey);
        DataEventTypeEnum eventType;
        String id;
        if (exist == null) {
            // 创建分支：自控全字段，规避 createOrUpdate 的随机 appSecret 覆盖
            id = UUIDUtils.getInstance().generateShortUuid();
            Timestamp now = new Timestamp(System.currentTimeMillis());
            AppAuthDO insert = AppAuthDO.builder()
                    .id(id)
                    .appKey(appKey)
                    .appSecret(appSecret)
                    .userId(appKey)
                    .open(open)
                    .enabled(enabled)
                    .dateCreated(now)
                    .dateUpdated(now)
                    .build();
            appAuthMapper.insertSelective(insert);
            eventType = DataEventTypeEnum.CREATE;
            log.info("[appauth-spi] 创建 app_auth 记录 appKey={} id={}", appKey, id);
        } else {
            // 更新分支：updateSelective 的 SET 子句不含 app_key（appKey 不可变），含 appSecret/enabled/open
            id = exist.getId();
            AppAuthDO update = AppAuthDO.builder()
                    .id(id)
                    .appKey(appKey)
                    .appSecret(appSecret)
                    .open(open)
                    .enabled(enabled)
                    .dateUpdated(new Timestamp(System.currentTimeMillis()))
                    .build();
            appAuthMapper.updateSelective(update);
            eventType = DataEventTypeEnum.UPDATE;
            log.info("[appauth-spi] 更新 app_auth 记录 appKey={} id={}", appKey, id);
        }

        // 手动 publishEvent 触发 websocket 推送（复刻 createOrUpdate L273-281，但去掉随机覆盖）
        AppAuthData data = AppAuthData.builder()
                .appKey(appKey)
                .appSecret(appSecret)
                .enabled(enabled)
                .open(open)
                .build();
        eventPublisher.publishEvent(
                new DataChangedEvent(ConfigGroupEnum.APP_AUTH, eventType, Collections.singletonList(data)));

        return ShenyuAdminResult.success(new CustomAppAuthCreateResp(id, appKey));
    }
}
