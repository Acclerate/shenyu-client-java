/*
 * PAY 侧签名配置属性，绑定 pay.sign.*。
 * 只需 2 个密钥路径 + 回调地址。
 */
package org.apache.shenyu.demo.sign.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PaySignProperties.
 *
 * <p>PAY 侧只需持有：支付服务私钥（加签响应/回调）+ 业务系统公钥（验签入站请求）。
 */
@ConfigurationProperties(prefix = "pay.sign")
public class PaySignProperties {

    /**
     * 支付服务私钥路径（加签响应/回调）。
     */
    private String payPrivateKey;

    /**
     * 业务系统公钥路径（验签入站请求）。
     */
    private String bizPublicKey;

    /**
     * 时间戳防重放允许偏差（秒）。
     */
    private long timestampToleranceSeconds = 300L;

    /**
     * 业务系统回调接收地址（PAY 直连 BIZ 回调，不经网关）。
     */
    private String bizCallbackUrl;

    /**
     * 是否在 PAY 侧验签入站请求.
     *
     * <p>阶段1直连：true（PAY 是唯一验签方）。
     * <p>阶段3经网关：false（验签已上移到网关 SignPlugin，请求到达 PAY 说明已被信任；
     *   且网关 ContextPathPlugin 会剥离 contextPath，PAY 侧路径与 BIZ 加签路径不一致，验签必败）。
     */
    private boolean signVerifyEnabled = true;

    public String getPayPrivateKey() {
        return payPrivateKey;
    }

    public void setPayPrivateKey(final String payPrivateKey) {
        this.payPrivateKey = payPrivateKey;
    }

    public String getBizPublicKey() {
        return bizPublicKey;
    }

    public void setBizPublicKey(final String bizPublicKey) {
        this.bizPublicKey = bizPublicKey;
    }

    public long getTimestampToleranceSeconds() {
        return timestampToleranceSeconds;
    }

    public void setTimestampToleranceSeconds(final long timestampToleranceSeconds) {
        this.timestampToleranceSeconds = timestampToleranceSeconds;
    }

    public String getBizCallbackUrl() {
        return bizCallbackUrl;
    }

    public void setBizCallbackUrl(final String bizCallbackUrl) {
        this.bizCallbackUrl = bizCallbackUrl;
    }

    public boolean isSignVerifyEnabled() {
        return signVerifyEnabled;
    }

    public void setSignVerifyEnabled(final boolean signVerifyEnabled) {
        this.signVerifyEnabled = signVerifyEnabled;
    }
}
