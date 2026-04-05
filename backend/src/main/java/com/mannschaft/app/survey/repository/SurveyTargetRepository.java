package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * アンケート配信対象リポジトリ。
 */
public interface SurveyTargetRepository extends JpaRepository<SurveyTargetEntity, Long> {

    /**
     * アンケートの配信対象を取得する。
     */
    List<SurveyTargetEntity> findBySurveyId(Long surveyId);

    /**
     * アンケート・ユーザーが配信対象か確認する。
     */
    boolean existsBySurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * アンケートの配信対象数を取得する。
     */
    long countBySurveyId(Long surveyId);

    /**
     * アンケートの配信対象を全削除する。
     */
    void deleteBySurveyId(Long surveyId);
}
