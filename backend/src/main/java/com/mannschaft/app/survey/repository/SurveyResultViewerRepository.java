package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyResultViewerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * アンケート結果閲覧者リポジトリ。
 */
public interface SurveyResultViewerRepository extends JpaRepository<SurveyResultViewerEntity, Long> {

    /**
     * アンケートの結果閲覧者を取得する。
     */
    List<SurveyResultViewerEntity> findBySurveyId(Long surveyId);

    /**
     * アンケート・ユーザーが結果閲覧者か確認する。
     */
    boolean existsBySurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * アンケートの結果閲覧者を全削除する。
     */
    void deleteBySurveyId(Long surveyId);
}
