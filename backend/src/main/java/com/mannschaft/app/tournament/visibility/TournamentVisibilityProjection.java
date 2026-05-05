package com.mannschaft.app.tournament.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.tournament.entity.TournamentEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。</p>
 *
 * <p>Repository は {@code id, scope_type='ORGANIZATION', organization_id, created_by, status,
 * visibility} を JPQL のコンストラクタ式 1 SQL で取得し、本 record にバインドする。
 * Tournament は {@code organization_id} のみで参照されるためスコープは常に
 * {@code "ORGANIZATION"} 固定。</p>
 *
 * <p>tournaments テーブルの {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の行は
 * 取得段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を Projection で再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>本機能は CUSTOM_TEMPLATE / FOLLOWERS_ONLY / CUSTOM を持たないため、
 * {@link #visibilityTemplateId()} は常に {@code null} を返す。</p>
 *
 * <p>{@link VisibilityProjection#visibility()} の戻り型 {@link Object} と record コンポーネント
 * accessor の衝突を避けるため、本 record の機能 enum 値は {@code tournamentVisibility} という
 * フィールド名で保持し、{@link #visibility()} は {@link Object} 戻り型でその値を返す。</p>
 *
 * @param id                   tournament_id
 * @param scopeType            常に {@code "ORGANIZATION"}
 * @param scopeId              organization_id
 * @param authorUserId         tournaments.created_by
 * @param status               tournaments.status（status 軸正規化に利用）
 * @param tournamentVisibility tournaments.visibility（StandardVisibility 正規化に利用）
 */
public record TournamentVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        TournamentStatus status,
        TournamentVisibility tournamentVisibility) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    @Override
    public Object visibility() {
        return tournamentVisibility;
    }
}
