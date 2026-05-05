package com.mannschaft.app.jobmatching.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.jobmatching.entity.JobPostingEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。</p>
 *
 * <p>Repository は {@code id, scope_type, scope_id, created_by, status, visibility} を
 * JPQL のコンストラクタ式 1 SQL で取得し、本 record にバインドする。
 *
 * <p>{@code job_postings} テーブルは {@code @SQLRestriction("deleted_at IS NULL")} により
 * 論理削除済の行は取得段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を Projection で再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>{@code job_postings} は {@code team_id} のみを持つため scopeType は常に {@code "TEAM"} で固定される。
 * organization スコープの求人は F13.1 第三版以降の拡張で追加される予定（現時点では未対応）。</p>
 *
 * <p>本機能は {@link com.mannschaft.app.common.visibility.StandardVisibility#CUSTOM_TEMPLATE}
 * を取り得る（{@link VisibilityScope#CUSTOM_TEMPLATE}）が、現行 DDL では
 * {@code visibility_template_id} カラムを持たないため、Phase C 時点では常に {@code null} を返す。
 * F01.7 と接続される Phase 13.1.2 以降で本フィールドを実カラムに差し替えること。</p>
 *
 * <p>{@link VisibilityProjection#visibility()} の戻り型 {@link Object} と record コンポーネント
 * accessor の衝突を避けるため、本 record の機能 enum 値は {@code visibilityScope} という
 * フィールド名で保持し、{@link #visibility()} は {@link Object} 戻り型でその値を返す。</p>
 *
 * @param id              job_posting_id
 * @param scopeType       常に {@code "TEAM"}
 * @param scopeId         team_id
 * @param authorUserId    job_postings.created_by_user_id（NOT NULL）
 * @param status          job_postings.status（status 軸正規化に利用）
 * @param visibilityScope job_postings.visibility_scope（StandardVisibility 正規化に利用）
 */
public record JobPostingVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        JobPostingStatus status,
        VisibilityScope visibilityScope) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        // F13.1 現行 DDL では visibility_template_id カラム未導入。Phase 13.1.2 以降で接続。
        return null;
    }

    @Override
    public Object visibility() {
        return visibilityScope;
    }
}
