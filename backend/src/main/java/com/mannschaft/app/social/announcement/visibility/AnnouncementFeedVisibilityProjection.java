package com.mannschaft.app.social.announcement.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * お知らせウィジェットフィード（{@code announcement_feeds}）の可視性判定に必要な
 * 最小属性を保持する射影（Projection）（F02.6 / F08.9 P4b）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §8.1。</p>
 *
 * <p>{@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} が参照する
 * フィールドを最小化し、SQL 1 本での射影取得を実現する。</p>
 *
 * <p><strong>スコープの決定規則</strong>:
 * <ul>
 *   <li>チームスコープ: {@code scopeType="TEAM"}, {@code scopeId=teamId}</li>
 *   <li>組織スコープ: {@code scopeType="ORGANIZATION"}, {@code scopeId=organizationId}</li>
 *   <li>委員会・広告主スコープ: {@code scopeType=AnnouncementScopeType.name()}, {@code scopeId}</li>
 * </ul>
 * </p>
 *
 * <p><strong>F08.9 P4b ペイウォール連結</strong>: {@code visibility=CUSTOM} のとき
 * {@link AnnouncementFeedVisibilityResolver#evaluateCustom} が
 * {@code sourceType + sourceId} を用いて
 * {@link com.mannschaft.app.payment.service.PaymentGateService#checkAccess} を呼ぶ。</p>
 *
 * @param id                   {@code announcement_feeds.id}
 * @param scopeType            スコープ種別文字列（{@code "TEAM"} / {@code "ORGANIZATION"} 等）
 * @param scopeId              スコープ ID
 * @param authorUserId         {@code announcement_feeds.author_id}（PRIVATE 判定用・NULL 可）
 * @param visibilityTemplateId 常に {@code null}（お知らせは CUSTOM_TEMPLATE 非対応）
 * @param visibility           {@link AnnouncementFeedVisibility} 値
 * @param sourceType           元コンテンツ種別（{@code "BLOG_POST"} 等・CUSTOM 判定でコンテンツタイプとして使用）
 * @param sourceId             元コンテンツ ID（CUSTOM 判定でコンテンツ ID として使用）
 */
public record AnnouncementFeedVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        Long visibilityTemplateId,
        AnnouncementFeedVisibility visibility,
        String sourceType,
        Long sourceId) implements VisibilityProjection {

    /**
     * {@link com.mannschaft.app.social.announcement.AnnouncementFeedEntity} から
     * {@code AnnouncementFeedVisibilityProjection} を生成するファクトリ。
     *
     * <p>Entity の {@code visibility} 文字列を {@link AnnouncementFeedVisibility} enum に変換する。
     * 既知値以外（不正データ）は {@link AnnouncementFeedVisibility#MEMBERS_AND_ABOVE} に倒す
     * （fail-closed: 不明 visibility は最も制限的な既知値として扱う）。</p>
     *
     * @param entity お知らせフィードエンティティ（non-null）
     * @return 変換済み Projection
     */
    public static AnnouncementFeedVisibilityProjection from(
            com.mannschaft.app.social.announcement.AnnouncementFeedEntity entity) {
        AnnouncementFeedVisibility vis = parseVisibility(entity.getVisibility());
        String scopeType = entity.getScopeType() != null ? entity.getScopeType().name() : null;
        String sourceType = entity.getSourceType() != null ? entity.getSourceType().name() : null;
        return new AnnouncementFeedVisibilityProjection(
                entity.getId(),
                scopeType,
                entity.getScopeId(),
                entity.getAuthorId(),
                null,   // visibilityTemplateId: お知らせは CUSTOM_TEMPLATE 非対応
                vis,
                sourceType,
                entity.getSourceId());
    }

    /**
     * {@code visibility} 文字列を {@link AnnouncementFeedVisibility} に変換する。
     *
     * <p>既知値以外は fail-closed で {@link AnnouncementFeedVisibility#MEMBERS_AND_ABOVE} を返す。</p>
     *
     * @param visibility DB 格納文字列（null 可）
     * @return 対応する enum 値（null 不可）
     */
    private static AnnouncementFeedVisibility parseVisibility(String visibility) {
        if (visibility == null) {
            // fail-closed: visibility 不明は最も制限的な値に倒す
            return AnnouncementFeedVisibility.MEMBERS_AND_ABOVE;
        }
        try {
            return AnnouncementFeedVisibility.valueOf(visibility);
        } catch (IllegalArgumentException e) {
            // fail-closed: 未知の値は最も制限的な既知値として扱う（不正データ保護）
            return AnnouncementFeedVisibility.MEMBERS_AND_ABOVE;
        }
    }
}
