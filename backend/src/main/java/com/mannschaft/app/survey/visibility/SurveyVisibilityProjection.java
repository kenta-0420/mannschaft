package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;

import java.time.LocalDateTime;

/**
 * F00 共通可視性基盤の {@link com.mannschaft.app.survey.entity.SurveyEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.1.4 / §7.5。</p>
 *
 * <p>{@code SurveyRepository#findVisibilityProjectionsByIdIn} が JPQL のコンストラクタ式
 * 1 SQL で {@code id, scope_type, scope_id, created_by, status, results_visibility, expires_at}
 * を取得し、本 record にバインドする。</p>
 *
 * <p>{@code surveys} テーブルの {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の
 * 行は取得段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED}
 * を Projection で再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>本機能は CUSTOM_TEMPLATE / FOLLOWERS_ONLY を持たないため、{@link #visibilityTemplateId()}
 * は常に {@code null} を返す。</p>
 *
 * <p>CUSTOM 経路 (AFTER_RESPONSE / AFTER_CLOSE / VIEWERS_ONLY) の判定に必要な
 * {@code expiresAt} を含めることで、Resolver が単独で時刻軸判定を完結できるようにする
 * （{@link SurveyVisibilityResolver#evaluateCustom} 内での DB 再フェッチを避ける）。</p>
 *
 * @param id                 survey_id
 * @param scopeType          {@code "TEAM"} または {@code "ORGANIZATION"}
 * @param scopeId            team_id または organization_id
 * @param authorUserId       surveys.created_by（{@code null} 可）
 * @param status             surveys.status（status 軸正規化に利用）
 * @param resultsVisibility  surveys.results_visibility（StandardVisibility 正規化に利用）
 * @param expiresAt          surveys.expires_at（AFTER_CLOSE 判定の閾値、{@code null} 可 → fail-closed）
 */
public record SurveyVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        SurveyStatus status,
        ResultsVisibility resultsVisibility,
        LocalDateTime expiresAt) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    @Override
    public Object visibility() {
        return resultsVisibility;
    }
}
