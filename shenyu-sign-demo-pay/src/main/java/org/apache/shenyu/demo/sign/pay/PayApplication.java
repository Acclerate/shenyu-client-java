/*
 * PAY（支付服务）启动类。
 * 端口 8392，仅持有 pay-private-key + biz-public-key 两个密钥。
 * 注册到 ShenYu 网关作为上游服务。
 */
package org.apache.shenyu.demo.sign.pay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PayApplication.
 *
 * <p>PAY 角色（支付服务）：验签业务系统入站请求 + 用私钥加签响应/回调。
 * 注册到 ShenYu 网关作为上游服务（contextPath: /pay-demo）。
 */
@SpringBootApplication
public class PayApplication {

    /**
     * Main entry.
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}
