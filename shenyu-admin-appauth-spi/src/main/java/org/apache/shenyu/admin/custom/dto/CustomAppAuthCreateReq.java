package org.apache.shenyu.admin.custom.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * POST /appAuth/customCreate 请求体。
 *
 * <p>用于在 shenyu-admin 的 app_auth 表 upsert 一条「指定 appKey」的记录，并把 appSecret 字段
 * 写为 RSA 公钥 PEM（设计方案 v2.0 的实时公钥源：app_auth.app_secret 承载公钥，语义重载）。
 *
 * <p>与原生 /appAuth/apply 的区别：apply 会用 SignUtils.generateKey() 随机覆盖 appKey，
 * 而本接口严格使用入参 appKey，允许调用方（erpm-pay-center）用 pay_app_config.app_key 对齐。
 */
@Data
public class CustomAppAuthCreateReq {

    /**
     * 应用标识。必须与网关验签请求头 X-Pay-App-Key 一致，对应 erpm-pay-center 的 pay_app_config.app_key。
     * 合法字符集 [A-Za-z0-9_-]，长度 1-64（与 paycenter 的 appKey 校验规则对齐）。
     */
    @NotBlank(message = "appKey 不能为空")
    @Size(max = 64, message = "appKey 长度不能超过 64")
    private String appKey;

    /**
     * 验签凭证。本方案中承载 RSA 公钥 PEM 文本（-----BEGIN PUBLIC KEY----- 头尾）。
     * 对应 erpm-pay-center 的 pay_app_config.app_public_key（normalizeToPem 规范化后）。
     * app_secret 列已扩到 VARCHAR(4096)，足够容纳 RSA-4096 PEM。
     */
    @NotBlank(message = "appSecret 不能为空")
    private String appSecret;

    /**
     * 是否启用。验签需要 enabled=true。
     *
     * <p>PATCH 语义：null=未传该字段。创建时默认 true；<b>更新时保留已存在记录的现值</b>
     * （防止「只轮换公钥」把已禁用的 appKey 静默重新启用）。
     */
    private Boolean enabled;

    /**
     * 是否开启路径白名单。本方案不开白名单（所有走 sign 的路径都验签）。
     *
     * <p>PATCH 语义：null=未传该字段。创建时默认 false；更新时保留已存在记录的现值。
     */
    private Boolean open;
}
