/*
 * BIZ（业务系统）启动类。
 * 端口 8391，仅持有 biz-private-key + pay-public-key 两个密钥。
 */
package org.apache.shenyu.demo.sign.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BizApplication.
 *
 * <p>BIZ 角色（业务系统）：发起支付请求（用 biz-private 加签）+ 接收回调（用 pay-public 验签）。
 * 不注册到 ShenYu 网关（它是调用方，不是上游服务）。
 */
@SpringBootApplication
public class BizApplication {

    /**
     * Main entry.
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(BizApplication.class, args);
    }
}
