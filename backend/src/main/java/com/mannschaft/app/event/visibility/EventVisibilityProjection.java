package com.mannschaft.app.event.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventVisibility;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.event.entity.EventEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。</p>
 *
 * <p>Repository は {@code id, scope_type, scope_id, created_by, status, visibility} を
 * JPQL のコンストラクタ式 1 SQL で取得し、本 record にバインドする。
 *
 * <p>events テーブルの {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の行は
 * 取得段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を Projection で再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>本機能は CUSTOM_TEMPLATE / FOLLOWERS_ONLY を持たないため、{@link #visibilityTemplateId()}
 * は常に {@code null} を返す。</p>
 *
 * <p>{@link VisibilityProjection#visibility()} の戻り型 {@link Object} と record コンポーネント
 * accessor の衝突を避けるため、本 record の機能 enum 値は {@code eventVisibility} という
 * フィールド名で保持し、{@link #visibility()} は {@link Object} 戻り型でその値を返す。</p>
 *
 * @param id              event_id
 * @param scopeType       {@code "TEAM"} または {@code "ORGANIZATION"}
 * @param scopeId         team_id または organization_id
 * @param authorUserId    events.created_by（{@code null} 可）
 * @param status          events.status（status 軸正規化に利用）
 * @param eventVisibility events.visibility（StandardVisibility 正規化に利用）
 */
public record EventVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        EventStatus status,
        EventVisibility eventVisibility) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    @Override
    public Object visibility() {
        return eventVisibility;
    }
}
