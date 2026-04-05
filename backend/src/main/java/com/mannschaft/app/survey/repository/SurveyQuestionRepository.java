package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * アンケート設問リポジトリ。
 */
public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestionEntity, Long> {

    /**
     * アンケートの設問を表示順で取得する。
     */
    List<SurveyQuestionEntity> findBySurveyIdOrderByDisplayOrderAsc(Long surveyId);

    /**
     * アンケートの設問数を取得する。
     */
    long countBySurveyId(Long surveyId);

    /**
     * アンケートの設問を全削除する。
     */
    void deleteBySurveyId(Long surveyId);
}
