package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.entity.SurveyEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
