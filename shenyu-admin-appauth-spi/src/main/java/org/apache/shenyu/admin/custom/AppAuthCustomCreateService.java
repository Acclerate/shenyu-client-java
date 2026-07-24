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

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Collections;

/**
 * app_auth 自定义 upsert + 公钥推送服务。
 *
 * <p><b>公钥源（唯一）</b>：网关侧 shenyu-sign-gateway-spi 的
 * {@code SignCacheBizPublicKeyProvider}（实现 AuthDataSubscriber），
 * 从 <b>app_auth 表</b>（APP_AUTH websocket 事件）读取公钥（app_secret 列承载 RSA 公钥 PEM）。
 * 本服务落库 app_auth 后手动发布 APP_AUTH 事件，admin 经 websocket 推送到网关。
 *
 * <p><b>为什么不复用原生 AppAuthService.createOrUpdate</b>：
 * createOrUpdate 的 id 为空分支会 setAppSecret(SignUtils.generateKey()) 随机覆盖 appSecret
 * （AppAuthServiceImpl L265），导致调用方传入的 PEM 公钥丢失。本服务自控全字段，规避该覆盖。
 *
 * <p><b>为什么不裸调 Mapper 不发事件</b>：裸 insertSelective/updateSelective 只落库，
 * 网关缓存不会收到推送（docs 反复强调「禁止直改 app_auth 表」就是这个原因）。
 * 本服务在落库后手动 publishEvent，与原生 updateDetail/createOrUpdate 的推送语义一致。
 *
 * <p><b>PEM fail-fast 前置校验</b>：appSecret 必须是可解析的 X.509 RSA 公钥 PEM，
 * 校验逻辑与网关侧 PemUtils.parsePem 同款（剥 BEGIN/END + 清空白 → Base64 → X509EncodedKeySpec
 * → KeyFactory("RSA")，并断言结果是 RSAPublicKey）。非法 PEM 直接返回 400，
 * 不落库、不推送——把故障从数据面运行时（该 appKey 全 401）左移到控制面配置时（当场报错）。
 *
 * <p><b>PATCH 语义（update 分支）</b>：enabled/open 入参为 null 表示「调用方未传该字段」，
 * update 时回落到已存在记录的现值而非常量默认值。否则「只轮换公钥」会把已禁用的
 * appKey 静默重新启用（安全事故）。仅 insert 分支（无现值可回落）才使用常量默认
 * enabled=true / open=false。
 *
 * <p>事务边界：本方法不加 @Transactional。insert/update 单条操作 + publishEvent，
 * 即便 publishEvent 失败也只是网关延迟同步（admin 可手动 syncData 兜底），不破坏 DB 一致性。
 * 若未来需要强一致，可加 @Transactional(rollbackFor=Exception.class)。
 */
@Slf4j
@RequiredArgsConstructor
public class AppAuthCustomCreateService {

    private static final String BEGIN_MARKER = "-----BEGIN PUBLIC KEY-----";

    private static final String END_MARKER = "-----END PUBLIC KEY-----";

    private final AppAuthMapper appAuthMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * upsert app_auth 并通过 APP_AUTH 事件推送到网关公钥源。
     *
     * <p>流程：
     * <ol>
     *   <li>PEM fail-fast 校验：appSecret 必须是合法 X.509 RSA 公钥 PEM，否则 400</li>
     *   <li>findByAppKey 查是否已存在（appKey 在 app_auth 表语义上唯一）</li>
     *   <li>enabled/open 缺省解析：显式传值优先；update 未传回落 exist 现值；insert 未传用默认 true/false</li>
     *   <li>不存在 → insertSelective 创建（完全自控字段：appKey/appSecret/enabled/open/userId/id）</li>
     *   <li>已存在 → updateSelective 更新（SET 子句含 appSecret/enabled/open，不含 app_key——appKey 不可变）</li>
     *   <li>publishEvent(APP_AUTH, CREATE/UPDATE) → 网关 SignCacheBizPublicKeyProvider</li>
     * </ol>
     *
     * @param req 请求体（appKey/appSecret 必填，enabled/open 可空——update 保留现值，insert 走默认）
     * @return ShenyuAdminResult，成功时 data 为 CustomAppAuthCreateResp（含 id + appKey）；PEM 非法时 400
     */
    public ShenyuAdminResult upsertAndPush(CustomAppAuthCreateReq req) {
        String appKey = req.getAppKey();
        String appSecret = req.getAppSecret();

        // P2⑤ fail-fast：非法 PEM 当场 400，绝不落库/推送（否则网关该 appKey 全线 401 且 admin 零报错）
        try {
            validateRsaPublicKeyPem(appSecret);
        } catch (Exception e) {
            log.warn("[appauth-spi] appKey={} 的 appSecret 不是合法 RSA 公钥 PEM，拒绝写入: {}", appKey, e.getMessage());
            return ShenyuAdminResult.error(400,
                    "appSecret is not a valid X.509 RSA public key PEM: " + e.getMessage());
        }

        AppAuthDO exist = appAuthMapper.findByAppKey(appKey);

        // P1① PATCH 语义：null=未传。update 回落 exist 现值；insert 用默认 true/false。
        // 防止「禁用的 appKey 只轮换公钥」被静默重新启用。
        boolean enabled = resolveFlag(req.getEnabled(), exist == null ? null : exist.getEnabled(), true);
        boolean open = resolveFlag(req.getOpen(), exist == null ? null : exist.getOpen(), false);

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
            log.info("[appauth-spi] 创建 app_auth 记录 appKey={} id={} enabled={} open={}", appKey, id, enabled, open);
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
            log.info("[appauth-spi] 更新 app_auth 记录 appKey={} id={} enabled={} open={}", appKey, id, enabled, open);
        }

        // APP_AUTH 事件 → 网关 SignCacheBizPublicKeyProvider（app_auth 源，唯一公钥路径）
        AppAuthData authData = AppAuthData.builder()
                .appKey(appKey)
                .appSecret(appSecret)
                .enabled(enabled)
                .open(open)
                .build();
        eventPublisher.publishEvent(
                new DataChangedEvent(ConfigGroupEnum.APP_AUTH, eventType, Collections.singletonList(authData)));

        return ShenyuAdminResult.success(new CustomAppAuthCreateResp(id, appKey));
    }

    /**
     * 三级缺省解析：显式入参 &gt; 已存在记录现值 &gt; 常量默认。
     *
     * @param reqValue     调用方显式传入的值（null=未传）
     * @param existValue   已存在记录的现值（insert 分支或 DB 列为 NULL 时为 null）
     * @param defaultValue 常量默认值（仅在前两者皆 null 时使用）
     * @return 解析后的最终值
     */
    private static boolean resolveFlag(final Boolean reqValue, final Boolean existValue, final boolean defaultValue) {
        if (reqValue != null) {
            return reqValue;
        }
        if (existValue != null) {
            return existValue;
        }
        return defaultValue;
    }

    /**
     * 校验字符串为合法的 X.509 RSA 公钥 PEM（与网关侧 PemUtils.parsePem 同款逻辑）。
     *
     * <p>剥 BEGIN/END 标记 + 清全部空白（兼容 LF/CRLF/无换行）→ Base64 解码为 DER
     * → X509EncodedKeySpec → KeyFactory("RSA").generatePublic，并断言结果是 RSAPublicKey。
     *
     * @param pem 待校验的 PEM 字符串
     * @throws Exception 任一步失败（Base64 非法 / DER 结构错 / 非 RSA 公钥等）
     */
    private static void validateRsaPublicKeyPem(final String pem) throws Exception {
        final String base64 = pem.replace(BEGIN_MARKER, "")
                .replace(END_MARKER, "")
                .replaceAll("\\s", "");
        final byte[] der = Base64.getDecoder().decode(base64);
        final java.security.PublicKey key =
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (!(key instanceof RSAPublicKey)) {
            throw new IllegalArgumentException("parsed key is not an RSA public key: " + key.getAlgorithm());
        }
    }
}
