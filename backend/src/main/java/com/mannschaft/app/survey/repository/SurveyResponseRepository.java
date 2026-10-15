package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * アンケート回答リポジトリ。
 */
public interface SurveyResponseRepository extends JpaRepository<SurveyResponseEntity, Long>, JpaSpecificationExecutor<SurveyResponseEntity> {

    /**
     * アンケート・ユーザーの回答が存在するか確認する。
     */
    boolean existsBySurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * アンケートの全回答を取得する。
     */
    List<SurveyResponseEntity> findBySurveyIdOrderByCreatedAtAsc(Long surveyId);

    /**
     * アンケート・設問別の回答を取得する。
     */
    List<SurveyResponseEntity> findBySurveyIdAndQuestionId(Long surveyId, Long questionId);

    /**
     * アンケート・ユーザーの回答を取得する。
     */
    List<SurveyResponseEntity> findBySurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * アンケート・ユーザーの回答を全削除する（再回答用）。
     */
    void deleteBySurveyIdAndUserId(Long surveyId, Long userId);

    /**
     * 設問・選択肢別の回答数を取得する。
     */
    long countBySurveyIdAndQuestionIdAndOptionId(Long surveyId, Long questionId, Long optionId);

    /**
     * アンケートのユニーク回答者数を取得する。
     */
    @Query("SELECT COUNT(DISTINCT r.userId) FROM SurveyResponseEntity r WHERE r.surveyId = :surveyId")
    long countDistinctUsersBySurveyId(@Param("surveyId") Long surveyId);
}
