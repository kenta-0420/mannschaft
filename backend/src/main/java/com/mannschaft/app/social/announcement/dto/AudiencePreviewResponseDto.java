package com.mannschaft.app.social.announcement.dto;

import java.util.List;
import java.util.UUID;

/**
 * 宛先プレビューのレスポンス（F01.2.1 §10.9）。
 *
 * @param resolvedTeamCount 宛先チーム数（「すべてのチーム」なら ACTIVE で加盟している全チーム数）
 * @param directMemberCount 直属メンバー数（送信者本人を除く組織メンバー。宛先を絞ったときだけ意味を持ち、それ以外は 0）
 * @param sampleTeams       宛先チームの先頭 50 件
 * @param groups            範囲を展開した後のグループ（並び順）
 * @param pushEnabled       push が送られるか（送信者が組織 ADMIN・MANAGE_CONTENT を持つ DEPUTY・SYSTEM_ADMIN のいずれかで、
 *                          かつチャネルが push を出す場合だけ true）
 * @param warnings          警告（{@code TEMPLATE_GROUPS_REMOVED} など）
 */
public record AudiencePreviewResponseDto(
        int resolvedTeamCount,
        int directMemberCount,
        List<SampleTeam> sampleTeams,
        List<GroupItem> groups,
        boolean pushEnabled,
        List<String> warnings) {

    /**
     * 宛先チームのサンプル。
     *
     * @param slug チームの slug
     * @param name チーム名
     */
    public record SampleTeam(String slug, String name) {
    }

    /**
     * 展開後のグループ。
     *
     * @param id   グループ ID
     * @param name グループ名
     */
    public record GroupItem(UUID id, String name) {
    }
}
