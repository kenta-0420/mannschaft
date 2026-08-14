package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 指定ユーザーが配信対象となっているアンケート ID を、与えた ID 集合の範囲で<b>一括</b>取得する。
     *
     * <p>F00 可視性基盤のバッチ判定（{@code filterAccessible}）で
     * {@link #existsBySurveyIdAndUserId} を行ごとに呼ぶと件数に比例した N+1 となり、
     * 設計書 {@code F00_content_visibility_resolver.md} のバッチ SQL 本数上限に反するため、
     * 1 本のクエリで名簿所属集合を引くための専用メソッド。</p>
     *
     * @param surveyIds 対象アンケート ID 集合（空を渡さないこと）
     * @param userId    閲覧者 user_id
     * @return {@code surveyIds} のうち当該ユーザーが配信対象であるアンケート ID
     */
    @Query("""
            SELECT t.surveyId FROM SurveyTargetEntity t
            WHERE t.surveyId IN :surveyIds AND t.userId = :userId
            """)
    List<Long> findTargetedSurveyIds(
            @Param("surveyIds") Collection<Long> surveyIds,
            @Param("userId") Long userId);

    /**
     * アンケートの配信対象数を取得する。
     */
    long countBySurveyId(Long surveyId);

    /**
     * アンケートの配信対象を全削除する。
     */
    void deleteBySurveyId(Long surveyId);
}
