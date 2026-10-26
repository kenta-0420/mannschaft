package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * アンケート選択肢リポジトリ。
 */
public interface SurveyOptionRepository extends JpaRepository<SurveyOptionEntity, Long> {

    /**
     * 設問の選択肢を表示順で取得する。
     */
    List<SurveyOptionEntity> findByQuestionIdOrderByDisplayOrderAsc(Long questionId);

    /**
     * 設問の選択肢を全削除する。
     */
    void deleteByQuestionId(Long questionId);
}
