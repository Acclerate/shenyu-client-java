package org.apache.shenyu.admin.custom;

import lombok.RequiredArgsConstructor;
import org.apache.shenyu.admin.custom.dto.CustomAppAuthCreateReq;
import org.apache.shenyu.admin.model.result.ShenyuAdminResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * app_auth 自定义创建/更新 REST 端点。
 *
 * <p>路径：POST /appAuth/customCreate（@RequestMapping("/appAuth") 与原生 AppAuthController 同前缀，
 * 但 mapping 不冲突——customCreate 是原生没有的子路径）。
 *
 * <p><b>鉴权</b>：shenyu-admin 的 ShiroConfiguration 对 /** 走 statelessAuth 全局拦截，
 * 本接口自动被覆盖，调用方（erpm-pay-center 的 ShenyuAdminClient）需带 X-Access-Token（admin 登录 token）。
 * 无需把本路径加进 shiro.whiteList——paycenter 的 ShenyuAdminClient 已有 token 缓存 + 401 自动重试机制，
 * 调用本接口与原来调 /plugin 走完全相同的 token 复用路径，无额外登录开销。
 *
 * <p><b>不加 @RequiresPermissions</b>：刻意不加权限注解（区别于原生 /appAuth/updateDetail 的
 * system:authen:edit）。原因：paycenter 用专设的 pusher 服务账号推送，token 校验已能挡住未授权访问，
 * 加权限注解反而要求运维在 admin 后台给 pusher 账号额外授予 system:authen:edit 权限，增加配置负担。
 *
 * <p><b>不使用 @Component</b>：由 {@link AppAuthCustomConfiguration} 显式 @Bean 装配，
 * 与 shenyu-sign-gateway-spi 的装配风格一致（铁律 2）。
 */
@RestController
@RequestMapping("/appAuth")
@RequiredArgsConstructor
public class AppAuthCustomCreateController {

    private final AppAuthCustomCreateService service;

    /**
     * 用指定 appKey 创建/更新 app_auth 记录，并触发 websocket 推送。
     *
     * @param req 请求体（appKey/appSecret 必填）
     * @return ShenyuAdminResult，data 为 {id, appKey}
     */
    @PostMapping("/customCreate")
    public ShenyuAdminResult customCreate(@RequestBody @Valid CustomAppAuthCreateReq req) {
        return service.upsertAndPush(req);
    }
}
