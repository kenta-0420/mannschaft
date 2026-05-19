package com.mannschaft.app.publicview.dto;

import com.mannschaft.app.team.entity.TeamEntity;

import java.time.LocalDate;

/**
 * F19.1 公開チームページ用の <strong>抑制版</strong>レスポンス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.3</p>
 *
 * <p>このレコードは認証不要エンドポイント
 * {@code GET /api/v1/public/teams/{id}}（publicview パッケージ）のレスポンスとして返却される。
 * F15.4 Phase 5-α の {@link com.mannschaft.app.team.dto.TeamPublicDetailResponse} とは
 * 別クラス（案 B 統合方針: 既存 8 件テスト破壊回避のため）。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>メンバー一覧 / 氏名 / メール / 電話 / 番地レベル住所</li>
 *   <li>{@code supporterEnabled} / {@code archivedAt} / {@code deletedAt} / {@code version}</li>
 *   <li>出席情報 / チャット履歴 / ファイル / 内部ドキュメント</li>
 * </ul>
 */
public record PublicTeamResponse(
        Long id,
        String name,
        String nameKana,
        String nickname1,
        String nickname2,
        String template,
        String prefecture,
        String city,
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
     * {@link TeamEntity} から公開 DTO を生成する。
     */
    public static PublicTeamResponse from(TeamEntity entity) {
        return new PublicTeamResponse(
                entity.getId(),
                entity.getName(),
                entity.getNameKana(),
                entity.getNickname1(),
                entity.getNickname2(),
                entity.getTemplate(),
                entity.getPrefecture(),
                entity.getCity(),
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
