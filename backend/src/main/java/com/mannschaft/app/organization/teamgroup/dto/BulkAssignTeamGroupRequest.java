package com.mannschaft.app.organization.teamgroup.dto;

import java.util.List;
import java.util.UUID;

/**
 * 一括グループ割当リクエスト（PUT /api/v1/organizations/{slug}/team-group-assignments。F01.2.1 §10.8）。
 *
 * <p>{@code teamSlugs} の検証（欠落・空配列・501 件以上・空白の要素は 400）は、組織 ADMIN であることの確認より
 * <b>後</b>に Service が行う（認可の順序: 認証 → 組織の存在 → 権限 → 入力検証。§10）。
 * そのため Bean Validation のアノテーションは付けない。</p>
 *
 * @param groupId   割り当てるチームグループの ID。null で未分類
 * @param teamSlugs 対象チームの slug（1〜500 件）
 */
public record BulkAssignTeamGroupRequest(UUID groupId, List<String> teamSlugs) {
}
