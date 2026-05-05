package com.mannschaft.app.bulletin.visibility;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.bulletin.entity.BulletinThreadEntity}
 * 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §7.5 / §12.3.1（visibility 概念新設機能の最小実装指針）。</p>
 *
 * <p>掲示板スレッドは現状 {@code visibility} カラムを持たない「所属固定」機能のため、
 * 本 Projection の {@link #visibility()} は機能側 enum ではなく
 * {@link StandardVisibility#MEMBERS_ONLY} を直接保持する固定値とする。
 * 後続の {@link BulletinThreadVisibilityResolver#toStandard} で同値が
 * 返されることで、所属メンバーのみ可視のセマンティクスが実現される。</p>
 *
 * <p>{@code bulletin_threads} テーブルの {@code @SQLRestriction("deleted_at IS NULL")}
 * により論理削除済の行は取得段階で除外されるため、Projection 側で
 * {@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を区別する必要は無い（取得不可 → fail-closed の自然な振る舞い）。</p>
 *
 * <p>scopeType は機能 enum {@code com.mannschaft.app.bulletin.ScopeType} の
 * 文字列表現（{@code "TEAM"} / {@code "ORGANIZATION"} / {@code "PERSONAL"}）を
 * そのまま保持する。{@code "PERSONAL"} は基底クラスの MEMBERS_ONLY 評価で
 * メンバー判定に hit せず fail-closed となる（最小実装の安全側挙動）。
 * 将来 PERSONAL スコープの判定ルールを変更する場合は別軍議で機能仕様策定が必要。</p>
 *
 * @param id           bulletin_thread_id
 * @param scopeType    {@code "TEAM"} / {@code "ORGANIZATION"} / {@code "PERSONAL"}
 * @param scopeId      team_id / organization_id / user_id
 * @param authorUserId bulletin_threads.author_id（{@code null} 可）
 */
public record BulletinThreadVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    @Override
    public Object visibility() {
        // §12.3.1: 機能側 visibility 概念を持たないため固定で MEMBERS_ONLY を返す。
        // Resolver#toStandard も同値を固定返却する設計。
        return StandardVisibility.MEMBERS_ONLY;
    }
}
