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
     */
    public static TeamPublicDetailResponse from(TeamEntity entity) {
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
                entity.getIconUrl(),
                entity.getBannerUrl(),
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
