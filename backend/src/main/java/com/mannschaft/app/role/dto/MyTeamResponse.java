package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自分の所属チームレスポンス（GET /api/v1/me/teams 用）。
 */
@Getter
@RequiredArgsConstructor
public class MyTeamResponse {

    private final Long id;
    private final String slug;
    /**
     * 親組織の数値 ID（F08.10 試合 API の org コンテキスト解決用・null 許容）。
     * チームが ACTIVE な組織に所属していない場合は null。
     * 試合 REST は {@code /organizations/{orgId}/teams/{teamId}/...}（数値）配下のため、
     * slug しか持たない {@code /teams/{id}/organizations} ではなく
     * 本フィールドから数値 orgId を直接取得できるようにする。
     *
     * <p><b>非推奨（F01.2.1）</b>: チームは複数の親組織に同時加盟できる。互換のため残すが、
     * 値は §9.3 の代表親組織（最初に成立した ACTIVE 加盟。同時刻なら organization_id 最小）に固定される。
     * 全親組織は {@link #organizations} を使うこと。</p>
     */
    private final Long organizationId;
    /**
     * チームが ACTIVE で加盟している全親組織（F01.2.1 §9.2 #7）。
     * 代表親組織が先頭。親組織が 0 件なら空配列。
     */
    private final List<ParentOrganization> organizations;
    private final String name;
    /** アイコンURL（DB未実装のため常にnull）。 */
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
    private final String role;
    private final LocalDateTime joinedAt;
    @JsonProperty("isArchived")
    private final boolean isArchived;
    /**
     * チームテンプレートスラッグ（"family", "school", "university" 等）。
     * F03.15 Phase 5b で家族チーム判定用に追加。
     * テンプレート未設定（汎用チーム）の場合は null。
     */
    private final String template;

    /** チームの親組織（id・slug・name）。 */
    @Getter
    @RequiredArgsConstructor
    public static class ParentOrganization {
        private final Long id;
        private final String slug;
        private final String name;
    }
}
