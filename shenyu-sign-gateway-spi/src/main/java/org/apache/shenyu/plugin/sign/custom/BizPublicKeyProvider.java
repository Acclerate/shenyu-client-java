package org.apache.shenyu.plugin.sign.custom;

import java.security.PublicKey;

/**
 * 业务公钥提供器（多租户）。
 *
 * <p>根据请求头 {@code X-Pay-App-Key} 携带的 appKey 从 Redis 获取对应的公钥，
 * 支持多业务方各自独立密钥对。
 */
public interface BizPublicKeyProvider {

    /**
     * 返回指定 appKey 对应的业务公钥。
     *
     * @param appKey 应用标识（来自请求头 X-Pay-App-Key）
     * @return 公钥
     * @throws Exception 读取失败
     */
    PublicKey currentKey(String appKey) throws Exception;
}
