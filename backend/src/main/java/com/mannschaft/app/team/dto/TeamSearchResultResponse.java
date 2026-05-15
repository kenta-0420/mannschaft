package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamEntity;

/**
 * F15.4: 組織内チーム検索の組織メンバー向け詳細版レスポンス。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3.3
 *
 * <p>抑制版 ({@link TeamPublicSummaryResponse}) の全フィールドに加え、
 * 組織メンバーのみに公開する以下のフィールドを含む:
 * <ul>
 *   <li>{@code visibility} — チーム公開範囲（{@code PUBLIC} / {@code ORGANIZATION_ONLY}）</li>
 *   <li>{@code bannerUrl} — バナー画像URL</li>
 *   <li>{@code supporterEnabled} — サポーター受け入れ可否</li>
 * </ul>
 *
 * <p><strong>Phase 1 未実装フィールド</strong>:
 * <ul>
 *   <li>{@code memberCount} — TeamEntity に集約済みフィールドが無く、毎リクエスト集約クエリを発行すると
 *       組織配下店舗数に比例して負荷が上がるため Phase 1 では省略する。Phase 3 で
 *       事前集計テーブルまたはマテリアライズドビューを検討する</li>
 * </ul>
 */
public record TeamSearchResultResponse(
        Long id,
        String name,
        String nameKana,
        String prefecture,
        String city,
        String template,
        String iconUrl,
        String visibility,
        String bannerUrl,
        Boolean supporterEnabled
) {

    /**
     * TeamEntity から詳細版 DTO を生成する。
     */
    public static TeamSearchResultResponse from(TeamEntity team) {
        return new TeamSearchResultResponse(
                team.getId(),
                team.getName(),
                team.getNameKana(),
                team.getPrefecture(),
                team.getCity(),
                team.getTemplate(),
                team.getIconUrl(),
                team.getVisibility() != null ? team.getVisibility().name() : null,
                team.getBannerUrl(),
                team.getSupporterEnabled()
        );
    }
}
