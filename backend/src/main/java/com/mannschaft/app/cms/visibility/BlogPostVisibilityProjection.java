package com.mannschaft.app.cms.visibility;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * BlogPost の可視性判定に必要な属性のみを保持する射影 (Projection)。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §8.1。
 *
 * <p>{@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * からアクセスする値を最小化し、SQL 1 本での射影取得を実現する。
 *
 * <p><strong>scopeType / scopeId の決定規則</strong>:
 * <ul>
 *   <li>{@code teamId != null} → {@code scopeType="TEAM"}, {@code scopeId=teamId}</li>
 *   <li>{@code organizationId != null} → {@code scopeType="ORGANIZATION"}, {@code scopeId=organizationId}</li>
 *   <li>個人ブログ ({@code userId} のみ) → {@code scopeType=null}, {@code scopeId=null}<br>
 *       スコープ概念がないため、{@code MEMBERS_ONLY} 等のスコープ依存値は fail-closed で false。
 *       {@code PUBLIC} と {@code PRIVATE} のみが意味を持つ。</li>
 * </ul>
 *
 * @param id                   blog_post_id
 * @param scopeType            "TEAM" / "ORGANIZATION" / null（個人ブログ）
 * @param scopeId              team_id / organization_id / null
 * @param authorUserId         author_id（PRIVATE 判定用）
 * @param visibilityTemplateId visibility_template_id（CUSTOM_TEMPLATE 用、null 可）
 * @param visibility           {@link Visibility} 値
 * @param status               {@link PostStatus} 値（status 軸ガード用）
 */
public record BlogPostVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        Long visibilityTemplateId,
        Visibility visibility,
        PostStatus status) implements VisibilityProjection {

    /**
     * {@code teamId / organizationId} 生値からスコープ種別を導出するファクトリ。
     *
     * <p>テストコードや手動射影で利用する。Spring Data JPA の constructor expression
     * からは canonical constructor を CASE 式と組み合わせて直接呼び出す
     * ({@link com.mannschaft.app.cms.repository.BlogPostRepository#findVisibilityProjectionsByIdIn})。
     *
     * @param id                   blog_post_id
     * @param teamId               team_id（null 可）
     * @param organizationId       organization_id（null 可）
     * @param authorUserId         author_id
     * @param visibilityTemplateId visibility_template_id
     * @param visibility           {@link Visibility} 値
     * @param status               {@link PostStatus} 値
     * @return scopeType / scopeId が導出された Projection
     */
    public static BlogPostVisibilityProjection of(
            Long id,
            Long teamId,
            Long organizationId,
            Long authorUserId,
            Long visibilityTemplateId,
            Visibility visibility,
            PostStatus status) {
        String scopeType = teamId != null ? "TEAM"
                : (organizationId != null ? "ORGANIZATION" : null);
        Long scopeId = teamId != null ? teamId : organizationId;
        return new BlogPostVisibilityProjection(
                id, scopeType, scopeId,
                authorUserId, visibilityTemplateId, visibility, status);
    }
}
