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
        /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
        String slug,
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
     *
     * <p>画像 URL 根治 Phase 1: {@code iconUrl} は DB の生 R2 キーをそのまま返さず、呼び出し側で
     * {@code MediaUrlResolver} を通して解決した署名付き表示 URL（絶対 URL）を受け取る。
     * 解決不能（null/失敗）の場合は null を渡す。バナーは抑制版のため含めない。</p>
     *
     * @param team            チームエンティティ
     * @param resolvedIconUrl 解決済みアイコン表示 URL（署名付き絶対 URL。未解決時は null）
     */
    public static TeamPublicSummaryResponse from(TeamEntity team, String resolvedIconUrl) {
        return new TeamPublicSummaryResponse(
                team.getSlug(),
                team.getSlug(),
                team.getName(),
                team.getNameKana(),
                team.getPrefecture(),
                team.getCity(),
                team.getTemplate(),
                resolvedIconUrl,
                team.getPrefectureCode(),
                team.getCityCode()
        );
    }
}
