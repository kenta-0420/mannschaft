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
                s.expiresAt)
            FROM SurveyEntity s
            WHERE s.id IN :ids AND s.deletedAt IS NULL
            """)
    List<SurveyVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
