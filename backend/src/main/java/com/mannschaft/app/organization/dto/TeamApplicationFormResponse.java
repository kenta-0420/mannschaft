package com.mannschaft.app.organization.dto;

import com.mannschaft.app.organization.TeamApplicationGroupMode;

import java.util.List;

/**
 * 加盟申請フォームの内容（F01.2.1 §10.3 {@code GET /api/v1/organizations/{slug}/team-application-form}）。
 *
 * @param organization 申請先の組織
 * @param guidance     組織が設定した案内文（null 可）
 * @param groupMode    申請時のグループ選択の実効値（§5.5）
 * @param groups       選べるチームグループ（並び順どおり。{@code groupMode = OFF} なら空配列）
 * @param myTeams      操作者が加盟操作権限を持つチーム（最大100件）と、この組織との関係
 */
public record TeamApplicationFormResponse(
        OrganizationRef organization,
        String guidance,
        TeamApplicationGroupMode groupMode,
        List<GroupOption> groups,
        List<MyTeam> myTeams
) {

    /** 組織の要約。 */
    public record OrganizationRef(String slug, String name, String iconUrl) {
    }

    /** 選べるチームグループ。{@code id} は UUID 文字列。 */
    public record GroupOption(String id, String name, String description) {
    }

    /**
     * 操作者のチームと、この組織との関係。
     *
     * @param affiliationStatus {@code NONE} / {@code APPLYING} / {@code INVITED} / {@code ACTIVE} /
     *                          {@code UNAVAILABLE}（制限中。冷却かブロックかは区別しない）
     */
    public record MyTeam(String slug, String name, String iconUrl, String affiliationStatus) {
    }
}
