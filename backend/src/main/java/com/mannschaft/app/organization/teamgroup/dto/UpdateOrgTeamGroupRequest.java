package com.mannschaft.app.organization.teamgroup.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チームグループ変更リクエスト（PATCH /api/v1/organizations/{slug}/team-groups/{groupId}）。
 *
 * <p>部分更新。{@code null}（キーを送らない・null を送る）は「変更しない」。
 * description に空文字（空白だけを含む）を送ると説明を消去する（F01.2.1 §10 共通事項: null と未送信は区別しない）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateOrgTeamGroupRequest {

    /** 新しいグループ名（任意） */
    private String name;

    /** 新しい補足説明（任意。空文字で消去） */
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
