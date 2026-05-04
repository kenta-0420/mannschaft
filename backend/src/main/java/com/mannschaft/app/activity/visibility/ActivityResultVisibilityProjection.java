package com.mannschaft.app.activity.visibility;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * F00 共通可視性基盤向け {@link com.mannschaft.app.activity.entity.ActivityResultEntity}
 * の射影 (Projection)。
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §4.6 の
 * {@code VisibilityProjection} 契約に準拠する。</p>
 *
 * <p><strong>Activity 固有の事情</strong>:</p>
 * <ul>
 *   <li>Activity は status 軸を持たないため、{@link com.mannschaft.app.common.visibility.ContentStatus}
 *       は常に {@code PUBLISHED}（Resolver 既定実装に委ねる）。</li>
 *   <li>{@link com.mannschaft.app.activity.entity.ActivityResultEntity} は
 *       {@code @SQLRestriction("deleted_at IS NULL")} で論理削除済を自動除外するため、
 *       本 Projection には DELETED 行は届かない。</li>
 *   <li>{@link com.mannschaft.app.activity.ActivityScopeType} には
 *       {@code TEAM / ORGANIZATION / COMMITTEE} の 3 値があるが、F00 Phase B 時点では
 *       {@code TEAM / ORGANIZATION} のみ {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService}
 *       が解決対象とする。COMMITTEE は将来 Phase で対応予定で、現時点では fail-closed
 *       （MEMBERS_ONLY を要求するロールが Snapshot に載らないため見えない）になる。</li>
 *   <li>visibility_template_id は Activity に存在しないため常に {@code null}。</li>
 * </ul>
 *
 * <p>本 Projection は Spring Data JPA のインターフェース Projection で
 * {@link com.mannschaft.app.activity.repository.ActivityResultRepository#findVisibilityProjectionsByIdIn(java.util.Collection)}
 * から JPQL 経由で生成される。</p>
 */
public interface ActivityResultVisibilityProjection extends VisibilityProjection {

    /** {@inheritDoc} ActivityResultEntity の主キー。 */
    @Override
    Long id();

    /**
     * {@inheritDoc}
     *
     * <p>JPQL 側で {@code CAST(scopeType AS string)} を用いて enum 名を文字列として返す。
     * 値は {@code "TEAM" / "ORGANIZATION" / "COMMITTEE"}。</p>
     */
    @Override
    String scopeType();

    /** {@inheritDoc} */
    @Override
    Long scopeId();

    /** {@inheritDoc} {@code created_by} カラム。 */
    @Override
    Long authorUserId();

    /**
     * {@inheritDoc}
     *
     * <p>Activity は CUSTOM_TEMPLATE 概念を持たないため常に {@code null} を返す。</p>
     */
    @Override
    default Long visibilityTemplateId() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>戻り値は {@link ActivityVisibility} のいずれか。Resolver 側で
     * {@link com.mannschaft.app.common.visibility.mapping.ActivityVisibilityMapper}
     * で正規化される。</p>
     */
    @Override
    ActivityVisibility visibility();
}
