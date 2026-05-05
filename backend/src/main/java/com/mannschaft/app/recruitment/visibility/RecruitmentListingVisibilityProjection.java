package com.mannschaft.app.recruitment.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentVisibility;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.recruitment.entity.RecruitmentListingEntity}
 * 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。</p>
 *
 * <p>Repository は {@code id, scope_type, scope_id, created_by, status,
 * visibility, visibility_template_id} を JPQL のコンストラクタ式 1 SQL で取得し、
 * 本 record にバインドする。</p>
 *
 * <p>recruitment_listings テーブルの {@code @SQLRestriction("deleted_at IS NULL")}
 * により論理削除済の行は取得段階で除外されるため、
 * {@link com.mannschaft.app.common.visibility.ContentStatus#DELETED} を Projection で
 * 再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>RecruitmentVisibility は CUSTOM_TEMPLATE を扱う（visibility_template_id は
 * V16.006 で recruitment_listings に追加済）。FOLLOWERS_ONLY は持たない。</p>
 *
 * <p>{@link VisibilityProjection#visibility()} の戻り型 {@link Object} と record
 * コンポーネント accessor の衝突を避けるため、本 record の機能 enum 値は
 * {@code recruitmentVisibility} という名前で保持し、{@link #visibility()} は
 * {@link Object} 戻り型でその値を返す。</p>
 *
 * @param id                    recruitment_listings.id
 * @param scopeType             {@code "TEAM"} または {@code "ORGANIZATION"}
 * @param scopeId               team_id または organization_id
 * @param authorUserId          recruitment_listings.created_by
 * @param visibilityTemplateId  visibility_template_id（CUSTOM_TEMPLATE 時のみ非 null）
 * @param status                recruitment_listings.status（status 軸正規化に利用）
 * @param recruitmentVisibility recruitment_listings.visibility（StandardVisibility 正規化に利用）
 */
public record RecruitmentListingVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        Long visibilityTemplateId,
        RecruitmentListingStatus status,
        RecruitmentVisibility recruitmentVisibility) implements VisibilityProjection {

    @Override
    public Object visibility() {
        return recruitmentVisibility;
    }
}
