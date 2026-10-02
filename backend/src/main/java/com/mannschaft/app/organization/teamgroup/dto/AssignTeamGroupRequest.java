package com.mannschaft.app.organization.teamgroup.dto;

import java.util.UUID;

/**
 * 1チームのグループ割当リクエスト（PUT /api/v1/organizations/{slug}/teams/{teamSlug}/team-group。F01.2.1 §10.8）。
 *
 * <p>「キーを送らない」と「null を送る」は区別しない（どちらも未分類へ戻す。§10 共通事項）。</p>
 *
 * @param groupId 割り当てるチームグループの ID。null で未分類
 */
public record AssignTeamGroupRequest(UUID groupId) {
}
