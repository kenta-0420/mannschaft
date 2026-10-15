package com.mannschaft.app.property.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.property.entity.PropertyWorkPackageEntity}
 * 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。
 * 機能側設計書: {@code docs/features/F09.13_property_history.md} §5.5 / §6.1。</p>
 *
 * <p>Repository 層に Projection 取得用クエリを追加せず、{@link PropertyWorkPackageVisibilityResolver}
 * 内で Entity を取得して本 record に詰め替える方式を採る。これにより 1-α で確定済の
 * Repository に手を入れず、Resolver 単独で Phase 1-β の責務を完結できる。</p>
 *
 * <p>{@code property_work_packages} テーブルは {@code @SQLRestriction("deleted_at IS NULL")}
 * により論理削除済の行は取得段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を Projection で別途扱う必要は無い。{@code @SQLRestriction} 通過後の status を
 * Resolver の {@code toContentStatus} で {@code DRAFT/PUBLISHED/ARCHIVED} に正規化する。</p>
 *
 * <p>{@code is_disclosable} フラグは F09.14 重説書での自動引用判定用であり、
 * 可視性判定とは無関係（誰が見られるかは visibility カラムだけで決まる）。
 * よって本 Projection には含めない。</p>
 *
 * @param id              property_work_packages.id
 * @param scopeType       {@code "TEAM"} または {@code "ORGANIZATION"}
 * @param scopeId         team_id または organization_id
 * @param authorUserId    property_work_packages.created_by（NOT NULL）
 * @param status          property_work_packages.status（status 軸正規化に利用）
 * @param visibilityValue property_work_packages.visibility（StandardVisibility 正規化に利用）
 */
public record PropertyWorkPackageVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        WorkPackageStatus status,
        WorkPackageVisibility visibilityValue) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        // F09.13 では CUSTOM_TEMPLATE 経路を持たない。
        return null;
    }

    @Override
    public Object visibility() {
        return visibilityValue;
    }
}
