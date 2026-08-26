package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamEntity;

import java.time.LocalDate;

/**
 * F15.4 Phase 5: 店舗詳細ページ未ログイン公開用の <strong>抑制版</strong>レスポンス。
 *
 * <p>設計書: {@code docs/features/F15.4_phase5_team_public_detail.md} §3
 *
 * <p>このレコードは認証不要エンドポイント
 * {@code GET /api/v1/public/teams/{id}} のレスポンスとして返却される。
 *
 * <h2>含めるフィールド（マスター裁可済み）</h2>
 * <ul>
 *   <li>{@code id} / {@code name} / {@code nameKana}</li>
 *   <li>{@code nickname1} / {@code nickname2}</li>
 *   <li>{@code template} / {@code prefecture} / {@code city}</li>
 *   <li>{@code iconUrl} / {@code bannerUrl} / {@code homepageUrl}</li>
 *   <li>{@code establishedDate} / {@code establishedDatePrecision}</li>
 *   <li>{@code philosophy} — 理念（管理画面の警告で公開承知の上で入力させる）</li>
 *   <li>{@code memberCount} — メンバー数（Phase 4 集計済み）</li>
 *   <li>{@code mapEmbedUrl} — Google Maps 埋め込み URL（Phase 5-β で追加済）</li>
 * </ul>
 *
 * <h2>禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>メンバー一覧 / 氏名 / メール / 電話 / 番地レベル住所</li>
 *   <li>{@code supporterEnabled} / {@code archivedAt} / {@code deletedAt} / {@code version}</li>
 *   <li>出席情報 / チャット履歴 / ファイル / 内部ドキュメント</li>
 * </ul>
 *
 * <p>禁則フィールドの混入を防ぐため、
 * {@code PublicTeamControllerTest#publicTeamResponse_doesNotLeakSensitiveFields()}
 * で JSON 文字列に禁則ワードが現れないことを継続的にチェックする。
 */
public record TeamPublicDetailResponse(
        String id,
        String name,
        String nameKana,
        String nickname1,
        String nickname2,
        String template,
        String prefecture,
        String city,
        String prefectureCode,
        String cityCode,
        String iconUrl,
        String bannerUrl,
        String homepageUrl,
        LocalDate establishedDate,
        String establishedDatePrecision,
        String philosophy,
        Integer memberCount,
        String mapEmbedUrl
) {

    /**
     * {@link TeamEntity} から抑制版 DTO を生成する。
     *
     * <p>memberCount は {@code TeamEntity#memberCount}（Long）→ Integer 変換。
     * 既存値が 0 / null の場合の挙動は呼び出し側に委ねる（本コンバータでは
     * null 安全のため defaultIfNull = 0 とする）。
     *
     * <p>画像 URL 根治 Phase 1: {@code iconUrl}/{@code bannerUrl} は DB の生 R2 キーをそのまま返さず、
     * 呼び出し側で {@code MediaUrlResolver} を通して解決した署名付き表示 URL（絶対 URL）を受け取る。
     * 解決不能（null/失敗）の場合は null を渡す。{@code mapEmbedUrl} は R2 キーではないため素通し。</p>
     *
     * @param entity           チームエンティティ
     * @param resolvedIconUrl  解決済みアイコン表示 URL（署名付き絶対 URL。未解決時は null）
     * @param resolvedBannerUrl 解決済みバナー表示 URL（署名付き絶対 URL。未解決時は null）
     */
    public static TeamPublicDetailResponse from(
            TeamEntity entity, String resolvedIconUrl, String resolvedBannerUrl) {
        return new TeamPublicDetailResponse(
                entity.getSlug(),
                entity.getName(),
                entity.getNameKana(),
                entity.getNickname1(),
                entity.getNickname2(),
                entity.getTemplate(),
                entity.getPrefecture(),
                entity.getCity(),
                entity.getPrefectureCode(),
                entity.getCityCode(),
                resolvedIconUrl,
                resolvedBannerUrl,
                entity.getHomepageUrl(),
                entity.getEstablishedDate(),
                entity.getEstablishedDatePrecision() != null
                        ? entity.getEstablishedDatePrecision().name()
                        : null,
                entity.getPhilosophy(),
                entity.getMemberCount() != null
                        ? Math.toIntExact(entity.getMemberCount())
                        : 0,
                entity.getMapEmbedUrl()
        );
    }
}
