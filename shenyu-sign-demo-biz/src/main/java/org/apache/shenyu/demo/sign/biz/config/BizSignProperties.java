/*
 * BIZ 侧签名配置属性，绑定 pay.sign.*。
 * 只需 2 个密钥路径 + 目标地址 + 回调地址。
 */
package org.apache.shenyu.demo.sign.biz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BizSignProperties.
 *
 * <p>BIZ 侧只需持有：业务系统私钥（加签出站请求）+ 支付服务公钥（验签响应/回调）。
 */
@ConfigurationProperties(prefix = "pay.sign")
public class BizSignProperties {

    /**
     * 业务系统私钥路径（加签出站请求）。
     */
    private String bizPrivateKey;

    /**
     * 支付服务公钥路径（验签响应/回调）。
     */
    private String payPublicKey;

    /**
     * 时间戳防重放允许偏差（秒）。
     */
    private long timestampToleranceSeconds = 300L;

    /**
     * 支付服务基础地址。
     * 阶段1直连：http://localhost:8392
     * 阶段2/3经网关：http://localhost:9196/pay-demo
     */
    private String payBaseUrl;

    /**
     * 业务系统回调接收地址（PAY 直连 BIZ 回调，不经网关）。
     */
    private String bizCallbackUrl;

    /**
     * 应用标识（多租户场景下网关据此从 Redis 获取对应公钥）。
     */
    private String appKey = "biz001";

    public String getBizPrivateKey() {
        return bizPrivateKey;
    }

    public void setBizPrivateKey(final String bizPrivateKey) {
        this.bizPrivateKey = bizPrivateKey;
    }

    public String getPayPublicKey() {
        return payPublicKey;
    }

    public void setPayPublicKey(final String payPublicKey) {
        this.payPublicKey = payPublicKey;
    }

    public long getTimestampToleranceSeconds() {
        return timestampToleranceSeconds;
    }

    public void setTimestampToleranceSeconds(final long timestampToleranceSeconds) {
        this.timestampToleranceSeconds = timestampToleranceSeconds;
    }

    public String getPayBaseUrl() {
        return payBaseUrl;
    }

    public void setPayBaseUrl(final String payBaseUrl) {
        this.payBaseUrl = payBaseUrl;
    }

    public String getBizCallbackUrl() {
        return bizCallbackUrl;
    }

    public void setBizCallbackUrl(final String bizCallbackUrl) {
        this.bizCallbackUrl = bizCallbackUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(final String appKey) {
        this.appKey = appKey;
    }
}
