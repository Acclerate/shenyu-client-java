package org.apache.shenyu.admin.custom.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /appAuth/customCreate 响应体（放在 ShenyuAdminResult.data 中）。
 *
 * <p>关键：回传 app_auth 记录的 id 和 appKey，解决原生 /appAuth/apply 响应 data=null
 * 不回传 appKey/id 的痛点（apply 后调用方无法得知生成的 appKey，导致无法反查）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomAppAuthCreateResp {

    /**
     * app_auth 记录主键 id。创建时由本扩展生成（UUIDUtils），更新时复用已存在记录的 id。
     */
    private String id;

    /**
     * 应用标识。回传入参 appKey，便于调用方确认落库值。
     */
    private String appKey;
}
