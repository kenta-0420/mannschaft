package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ベータ登録制限設定更新リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class UpdateBetaRestrictionRequest {

    @NotNull
    private Boolean isEnabled;

    /** 招待可能チームID上限（null=制限なし） */
    private Long maxTeamId;

    /** 招待可能組織ID上限（null=制限なし） */
    private Long maxOrgId;
}
