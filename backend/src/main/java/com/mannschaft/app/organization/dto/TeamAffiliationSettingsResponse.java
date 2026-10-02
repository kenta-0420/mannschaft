package com.mannschaft.app.organization.dto;

import com.mannschaft.app.organization.TeamApplicationGroupMode;

/**
 * チーム加盟の申請受付・グループ設定（F01.2.1 §10.2）。GET と PUT の応答で同じ形を返す。
 *
 * @param teamApplicationEnabled        チームからの加盟申請を受け付けるか
 * @param teamGroupsEnabled             チームグループ機能を使うか
 * @param applicationGroupMode          申請時のグループ選択（保存値）
 * @param effectiveApplicationGroupMode 申請時のグループ選択の実効値（§5.5。グループ機能 off なら OFF、
 *                                      REQUIRED で生存グループ0件なら OPTIONAL）
 * @param applicationGuidance           申請フォームに表示する案内文（null 可）
 * @param pendingApplicationCount       受付済みで未処理の申請（PENDING・TEAM_APPLY）の件数
 */
public record TeamAffiliationSettingsResponse(
        boolean teamApplicationEnabled,
        boolean teamGroupsEnabled,
        TeamApplicationGroupMode applicationGroupMode,
        TeamApplicationGroupMode effectiveApplicationGroupMode,
        String applicationGuidance,
        long pendingApplicationCount
) {
}
