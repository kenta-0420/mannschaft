package com.mannschaft.app.organization.teamgroup.dto;

import java.util.List;

/**
 * チームグループ一覧の応答（F01.2.1 §10.7。ページングなし）。
 *
 * @param data グループ（並び順どおり）
 * @param meta 件数情報
 */
public record OrgTeamGroupListResponse(List<OrgTeamGroupResponse> data, Meta meta) {

    /**
     * @param unassignedTeamCount 未分類の ACTIVE な加盟チーム数（削除済みグループを指す行を含む）
     * @param limit               1 組織あたりのグループ上限
     */
    public record Meta(long unassignedTeamCount, int limit) {
    }
}
