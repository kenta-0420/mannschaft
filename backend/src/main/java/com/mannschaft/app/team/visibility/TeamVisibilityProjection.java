package com.mannschaft.app.team.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.team.entity.TeamEntity;

import java.time.LocalDateTime;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.team.entity.TeamEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。</p>
 *
 * <p>F00 Phase D-γ — {@code TeamVisibilityResolver} が
 * {@code TeamRepository#findVisibilityProjectionsByIdIn} 経由で 1 SQL にて取得する。</p>
 *
 * <p>{@code teams} テーブルの {@code @SQLRestriction("deleted_at IS NULL")} により
 * 論理削除済の行は通常の JPA クエリから除外されるが、本 Projection では
 * {@code deletedAt} も射影することで {@code toContentStatus} での状態判定を可能にする。
 * なお、{@code @SQLRestriction} が適用された通常クエリでは {@code deletedAt != null} の
 * 行は取得されないため、{@code ContentStatus.DELETED} に到達するケースは
 * ネイティブクエリ経由の場合のみとなる（fail-closed として保持）。</p>
 *
 * <p>チームに作成者（{@code created_by}）の概念はないため {@link #authorUserId()} は
 * 常に {@code null} を返す。</p>
 *
 * <p>本機能は CUSTOM_TEMPLATE / FOLLOWERS_ONLY / CUSTOM 経路を持たないため、
 * {@link #visibilityTemplateId()} は常に {@code null} を返す。</p>
 *
 * @param id             team_id（コンテンツ ID）
 * @param teamId         team_id（スコープ ID）
 * @param teamVisibility チームの公開範囲 enum
 * @param archivedAt     アーカイブ日時（{@code null} = 非アーカイブ）
 * @param deletedAt      論理削除日時（{@code null} = 未削除）
 */
public record TeamVisibilityProjection(
        Long id,
        Long teamId,
        TeamEntity.Visibility teamVisibility,
        LocalDateTime archivedAt,
        LocalDateTime deletedAt) implements VisibilityProjection {

    @Override
    public String scopeType() {
        return "TEAM";
    }

    @Override
    public Long scopeId() {
        return teamId;
    }

    @Override
    public Long authorUserId() {
        // チームに作成者概念なし
        return null;
    }

    @Override
    public Object visibility() {
        return teamVisibility;
    }

    @Override
    public Long visibilityTemplateId() {
        return null;
    }
}
