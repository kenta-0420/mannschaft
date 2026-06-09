package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamEntity;

/**
 * F15.4: 組織内チーム検索の未ログイン／非メンバー向け抑制版レスポンス。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3.3
 *
 * <p>個人情報漏洩対策として、以下のフィールドは <strong>意図的に含めない</strong>:
 * <ul>
 *   <li>{@code memberCount} — メンバー数</li>
 *   <li>{@code bannerUrl} — バナー画像</li>
 *   <li>{@code homepageUrl} — 公式サイト</li>
 *   <li>{@code establishedDate} — 設立日</li>
 *   <li>{@code supporterEnabled} — サポーター受け入れ可否</li>
 *   <li>{@code archivedAt} — 凍結時刻</li>
 *   <li>{@code nickname1} / {@code nickname2} — 愛称</li>
 *   <li>連絡先（電話／メール／番地レベルの住所）</li>
 * </ul>
 *
 * <p>住所情報は {@code prefecture} と {@code city} までに限定する。
 */
public record TeamPublicSummaryResponse(
        String id,
        String name,
        String nameKana,
        String prefecture,
        String city,
        String template,
        String iconUrl,
        String prefectureCode,
        String cityCode
) {

    /**
     * TeamEntity から抑制版 DTO を生成する。
     *
     * <p>F22.1 市 Phase 2 足場C: 構造化キー {@code prefectureCode}/{@code cityCode} を追加で返す
     * （名称 {@code prefecture}/{@code city} も表示用に併存）。フィールド名は Jackson 既定の camelCase。</p>
     */
    public static TeamPublicSummaryResponse from(TeamEntity team) {
        return new TeamPublicSummaryResponse(
                team.getSlug(),
                team.getName(),
                team.getNameKana(),
                team.getPrefecture(),
                team.getCity(),
                team.getTemplate(),
                team.getIconUrl(),
                team.getPrefectureCode(),
                team.getCityCode()
        );
    }
}
