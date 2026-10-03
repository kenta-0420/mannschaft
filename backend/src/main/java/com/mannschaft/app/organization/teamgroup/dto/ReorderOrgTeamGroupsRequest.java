package com.mannschaft.app.organization.teamgroup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * チームグループ並び替えリクエスト（PUT /api/v1/organizations/{slug}/team-groups/order）。
 *
 * <p>組織の生存グループ全件の ID を、新しい並び順で送る。生存グループの集合と一致しなければ
 * 409 {@code ORG_068}（順序は変えない）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ReorderOrgTeamGroupsRequest {

    /** 新しい並び順のグループ ID（全件） */
    @NotNull
    private List<UUID> groupIds;
}
