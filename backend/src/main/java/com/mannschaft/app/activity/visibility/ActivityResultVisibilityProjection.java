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
 * <p><strong>実装方針</strong>: BlogPost と同様に record + JPQL constructor
 * expression で構築する。Spring Data JPA のインターフェース Projection は
 * record 風メソッド名 ({@code id()} 等) と相性が悪く（JavaBeans 規則
 * {@code getId()} を期待）、メソッド呼び出し時に
 * {@link IllegalArgumentException} を投げるため、record 形式に統一する。</p>
 *
 * <p><strong>Activity 固有の事情</strong>:</p>
 * <ul>
 *   <li>Activity は status 軸を持たないため、{@link com.mannschaft.app.common.visibility.ContentStatus}
 *       は常に {@code PUBLISHED}（Resolver 既定実装に委ねる）。</li>
 *   <li>{@link com.mannschaft.app.activity.entity.ActivityResultEntity} は
 *       {@code @SQLRestriction("deleted_at IS NULL")} で論理削除済を自動除外するが、
 *       本 Projection の JPQL でも明示的に {@code deletedAt IS NULL} を付ける
 *       （constructor expression 経由のため SQLRestriction が効かない可能性に備える）。</li>
 *   <li>{@link com.mannschaft.app.activity.ActivityScopeType} には
 *       {@code TEAM / ORGANIZATION / COMMITTEE} の 3 値があるが、F00 Phase B 時点では
 *       {@code TEAM / ORGANIZATION} のみ {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService}
 *       が解決対象とする。COMMITTEE は将来 Phase で対応予定で、現時点では fail-closed
 *       （MEMBERS_ONLY を要求するロールが Snapshot に載らないため見えない）になる。</li>
 *   <li>visibility_template_id は Activity に存在しないため常に {@code null}。</li>
 * </ul>
 *
 * @param id              activity_result_id
 * @param scopeType       "TEAM" / "ORGANIZATION" / "COMMITTEE"
 * @param scopeId         scope_id
 * @param authorUserId    created_by（作成者 user_id）
 * @param visibility      {@link ActivityVisibility} 値
 */
public record ActivityResultVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        ActivityVisibility visibility) implements VisibilityProjection {

    /**
     * Activity は CUSTOM_TEMPLATE 概念を持たないため常に {@code null} を返す。
     */
    @Override
    public Long visibilityTemplateId() {
        return null;
    }
}
