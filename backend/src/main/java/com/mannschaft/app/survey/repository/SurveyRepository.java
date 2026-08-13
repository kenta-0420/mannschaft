package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.visibility.SurveyVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * アンケートリポジトリ。
 */
public interface SurveyRepository extends JpaRepository<SurveyEntity, Long> {

    /**
     * スコープ別にアンケートをページング取得する。
     */
    Page<SurveyEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ・ステータス別にアンケートをページング取得する。
     */
    Page<SurveyEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, SurveyStatus status, Pageable pageable);

    /**
     * IDとスコープでアンケートを取得する。
     */
    Optional<SurveyEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    /**
     * 作成者別にアンケートを取得する。
     */
    List<SurveyEntity> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    /**
     * シリーズIDでアンケートを取得する。
     */
    List<SurveyEntity> findBySeriesIdOrderByCreatedAtDesc(String seriesId);

    /**
     * スコープのステータス別件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, SurveyStatus status);

    /**
     * F22.1 第二波: 指定スコープで、当該ユーザーが「未回答」の公開中（PUBLISHED）アンケートを
     * 直近作成順に取得する。
     *
     * <p>{@code survey_responses} に当該ユーザーの回答行が 1 件も存在しない PUBLISHED アンケートを
     * 対象とする（NOT EXISTS サブクエリ）。N+1 を避けるため 1 SQL で判定する。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @return 未回答の公開中アンケート（作成日時の降順）
     */
    @Query("""
            SELECT s FROM SurveyEntity s
            WHERE s.scopeType = :scopeType
              AND s.scopeId = :scopeId
              AND s.status = com.mannschaft.app.survey.SurveyStatus.PUBLISHED
              AND NOT EXISTS (
                  SELECT 1 FROM com.mannschaft.app.survey.entity.SurveyResponseEntity r
                  WHERE r.surveyId = s.id AND r.userId = :userId
              )
            ORDER BY s.createdAt DESC
            """)
    List<SurveyEntity> findUnansweredPublishedForUserInScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("userId") Long userId);

    /**
     * F00 共通可視性基盤 — {@link SurveyVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §6.3.2 工程 6。
     *
     * <p>{@code SurveyEntity} の {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の
     * 行は自動的に除外されるため、明示の WHERE 句でも念のため {@code deleted_at IS NULL} を
     * 重ねる（多層防御）。本メソッドは Resolver の
     * {@code AbstractContentVisibilityResolver#loadProjections} からのみ呼ばれ、戻り値の順序は
     * 保証しない。</p>
     *
     * @param ids 取得対象 survey_id 集合（空の場合は空 List を返す）
     * @return 実存する surveys の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.survey.visibility.SurveyVisibilityProjection(
                s.id,
                s.scopeType,
                s.scopeId,
                s.createdBy,
                s.status,
                s.resultsVisibility,
                s.expiresAt,
                s.includeSupporters,
                s.distributionMode)
            FROM SurveyEntity s
            WHERE s.id IN :ids AND s.deletedAt IS NULL
            """)
    List<SurveyVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
