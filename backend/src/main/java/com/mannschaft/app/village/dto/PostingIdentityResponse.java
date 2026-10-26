package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;

/**
 * 投稿主体 1 件のレスポンス DTO（F17.1 Phase 1 B9 §4.6）。
 *
 * <p>呼び出しユーザーが当該村で「誰として投稿できるか」を表す要素。</p>
 *
 * @param subjectType 主体種別（USER / TEAM / ORGANIZATION）
 * @param subjectId   主体 ID
 *                    （USER の場合は userId、TEAM の場合は teamId、ORGANIZATION の場合は organizationId）
 * @param displayName 表示名（USER ならニックネーム、TEAM/ORGANIZATION なら正式名称）
 * @param canPostAs   この主体として投稿可能か（Phase 1 では常に true。
 *                    将来 village_representatives 委任 / 一時的な権限剥奪等で false になりうる）
 */
public record PostingIdentityResponse(
        VillageSubjectType subjectType,
        Long subjectId,
        String displayName,
        boolean canPostAs
) {

    /** USER 主体エントリのファクトリ。 */
    public static PostingIdentityResponse user(Long userId, String displayName) {
        return new PostingIdentityResponse(VillageSubjectType.USER, userId, displayName, true);
    }

    /** TEAM 主体エントリのファクトリ。 */
    public static PostingIdentityResponse team(Long teamId, String displayName) {
        return new PostingIdentityResponse(VillageSubjectType.TEAM, teamId, displayName, true);
    }

    /** ORGANIZATION 主体エントリのファクトリ。 */
    public static PostingIdentityResponse organization(Long orgId, String displayName) {
        return new PostingIdentityResponse(VillageSubjectType.ORGANIZATION, orgId, displayName, true);
    }
}
