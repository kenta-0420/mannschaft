package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 自分の所属チームレスポンス（GET /api/v1/me/teams 用）。
 */
@Getter
@RequiredArgsConstructor
public class MyTeamResponse {

    private final Long id;
    /** URL 識別子（スラッグ値）。FE 契約互換のため JSON キーは publicId のまま（slug キー化は FE 移行 PR で実施）。 */
    private final String publicId;
    /**
     * 親組織の数値 ID（F08.10 試合 API の org コンテキスト解決用・null 許容）。
     * チームが ACTIVE な組織に所属していない場合は null。
     * 試合 REST は {@code /organizations/{orgId}/teams/{teamId}/...}（数値）配下のため、
     * publicId(スラッグ) しか持たない {@code /teams/{id}/organizations} ではなく
     * 本フィールドから数値 orgId を直接取得できるようにする。
     */
    private final Long organizationId;
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
}
