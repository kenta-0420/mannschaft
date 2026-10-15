package com.mannschaft.app.organization.teamgroup.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チームグループ作成リクエスト（POST /api/v1/organizations/{slug}/team-groups）。
 *
 * <p>name は前後の空白を除いて 1〜50 コードポイント、description は 200 コードポイントまで（任意）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateOrgTeamGroupRequest {

    /** グループ名（必須） */
    @NotNull
    private String name;

    /** 補足説明（任意） */
    private String description;

    @JsonIgnore
    @AssertTrue(message = "グループ名は前後の空白を除いて 1〜50 文字で入力してください")
    public boolean isNameLengthValid() {
        return name == null || OrgTeamGroupInputRules.isValidName(name);
    }

    @JsonIgnore
    @AssertTrue(message = "説明は 200 文字以内で入力してください")
    public boolean isDescriptionLengthValid() {
        return OrgTeamGroupInputRules.isValidDescription(description);
    }
}
