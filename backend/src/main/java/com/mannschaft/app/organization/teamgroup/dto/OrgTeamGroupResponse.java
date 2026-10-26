package com.mannschaft.app.organization.teamgroup.dto;

import java.util.UUID;

/**
 * チームグループ 1 件の応答（F01.2.1 §10.7）。
 *
 * @param id          グループ ID（UUID）
 * @param name        表示名
 * @param description 補足説明（無ければ null）
 * @param sortOrder   並び順（昇順。0 始まり）
 * @param teamCount   ACTIVE な加盟チーム数
 */
public record OrgTeamGroupResponse(UUID id, String name, String description, int sortOrder, long teamCount) {
}
