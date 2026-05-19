package com.mannschaft.app.publicview.dto;

import com.mannschaft.app.organization.entity.OrganizationEntity;

import java.time.LocalDate;

/**
 * F19.1 公開組織ページ用の <strong>抑制版</strong>レスポンス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.3</p>
 *
 * <p>このレコードは認証不要エンドポイント
 * {@code GET /api/v1/public/organizations/{id}} のレスポンスとして返却される。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>メンバー一覧 / 氏名 / メール / 電話 / 番地レベル住所</li>
 *   <li>{@code supporterEnabled} / {@code archivedAt} / {@code deletedAt} / {@code version}</li>
 *   <li>{@code parentOrganizationId}（階層情報は別エンドポイント担当）</li>
 *   <li>{@code profileVisibility}（内部設定のため非公開）</li>
 * </ul>
 *
 * <p>{@code philosophy} は {@code profile_visibility.philosophy = true} の場合のみ含める。</p>
 */
public record PublicOrganizationResponse(
        Long id,
        String name,
        String nameKana,
        String nickname1,
        String nickname2,
        String orgType,
        String prefecture,
        String city,
        String iconUrl,
        String bannerUrl,
        String homepageUrl,
        LocalDate establishedDate,
        String establishedDatePrecision,
        String philosophy,
        String mapEmbedUrl
) {

    /**
     * {@link OrganizationEntity} から公開 DTO を生成する。
     *
     * <p>{@code philosophy} は {@code profile_visibility.philosophy = true} の場合のみ含め、
     * それ以外では {@code null} を設定する。Phase 1 では {@code profileVisibility} の
     * 詳細解析は呼び出し側 Service の責務とし、本メソッドは entity 値を素直にコピーする。</p>
     */
    public static PublicOrganizationResponse from(OrganizationEntity entity, boolean philosophyVisible) {
        return new PublicOrganizationResponse(
                entity.getId(),
                entity.getName(),
                entity.getNameKana(),
                entity.getNickname1(),
                entity.getNickname2(),
                entity.getOrgType() != null ? entity.getOrgType().name() : null,
                entity.getPrefecture(),
                entity.getCity(),
                entity.getIconUrl(),
                entity.getBannerUrl(),
                entity.getHomepageUrl(),
                entity.getEstablishedDate(),
                entity.getEstablishedDatePrecision() != null
                        ? entity.getEstablishedDatePrecision().name()
                        : null,
                philosophyVisible ? entity.getPhilosophy() : null,
                entity.getMapEmbedUrl()
        );
    }
}
