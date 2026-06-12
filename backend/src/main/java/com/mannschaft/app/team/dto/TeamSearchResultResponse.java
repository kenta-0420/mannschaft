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
 *   <li>{@code visibility} — チーム公開範囲（{@code PUBLIC} / {@code GUESTS_AND_ABOVE} / {@code SUPPORTERS_AND_ABOVE} / {@code MEMBERS_AND_ABOVE}）</li>
 *   <li>{@code bannerUrl} — バナー画像URL</li>
 *   <li>{@code supporterEnabled} — サポーター受け入れ可否</li>
 *   <li>{@code memberCount} — チーム所属メンバー数（Phase 4 で {@code teams.member_count} 集約カラム経由で反映）</li>
 * </ul>
 *
 * <p><strong>memberCount の集約方針（Phase 4 〜）</strong>:
 * Phase 1 では集約クエリ高負荷を理由に省略していたが、Phase 4 で
 * {@code teams.member_count} カラム（V9.154 で追加）を非同期リスナー / 整合性バッチで
 * 維持する設計を導入したため、追加 DB 往復ゼロで返却可能となった。
 *
 * <p><strong>公開可視性の差分（設計書 §3.3）</strong>:
 * 未ログイン / 非メンバー向けの {@link TeamPublicSummaryResponse} には memberCount を
 * 意図的に含めない（連絡先・番地・メンバー規模は組織内情報のため）。
 */
public record TeamSearchResultResponse(
        String id,
        String name,
        String nameKana,
        String prefecture,
        String city,
        String template,
        String iconUrl,
        String visibility,
        String bannerUrl,
        Boolean supporterEnabled,
        Long memberCount,
        String prefectureCode,
        String cityCode
) {

    /**
     * TeamEntity から詳細版 DTO を生成する。
     *
     * <p>{@code memberCount} は {@link TeamEntity#getMemberCount()} を直接参照する
     * （集約クエリ不要）。null の場合は {@code 0L} にフォールバックする。
     *
     * <p>F22.1 市 Phase 2 足場C: 構造化キー {@code prefectureCode}/{@code cityCode} を追加で返す
     * （名称 {@code prefecture}/{@code city} も併存）。フィールド名は Jackson 既定の camelCase。</p>
     */
    public static TeamSearchResultResponse from(TeamEntity team) {
        return new TeamSearchResultResponse(
                team.getSlug(),
                team.getName(),
                team.getNameKana(),
                team.getPrefecture(),
                team.getCity(),
                team.getTemplate(),
                team.getIconUrl(),
                team.getVisibility() != null ? team.getVisibility().name() : null,
                team.getBannerUrl(),
                team.getSupporterEnabled(),
                team.getMemberCount() != null ? team.getMemberCount() : 0L,
                team.getPrefectureCode(),
                team.getCityCode()
        );
    }
}
