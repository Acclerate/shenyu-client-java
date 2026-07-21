package org.apache.shenyu.plugin.sign.custom;

import java.security.PublicKey;

/**
 * 业务公钥提供器（多租户）。
 *
 * <p>网关验签时，根据请求头 {@code X-Pay-App-Key} 携带的 appKey 解析出对应业务方的公钥。
 * 具体来源（Redis / admin plugin.config / HTTP 等）由实现类决定，本接口只定义契约，
 * 不绑定任何具体存储。
 */
public interface BizPublicKeyProvider {

    /**
     * 返回指定 appKey 对应的业务公钥。
     *
     * @param appKey 应用标识（来自请求头 X-Pay-App-Key）
     * @return 公钥
     * @throws Exception appKey 为空，或该业务方公钥不存在/读取失败时抛出
     */
    PublicKey currentKey(String appKey) throws Exception;
}
