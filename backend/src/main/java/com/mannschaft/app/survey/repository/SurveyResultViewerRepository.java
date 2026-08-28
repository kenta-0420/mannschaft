package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyResultViewerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 指定ユーザーが結果閲覧者となっているアンケート ID を、与えた ID 集合の範囲で<b>一括</b>取得する。
     *
     * <p>{@link #existsBySurveyIdAndUserId} をバッチ判定のループ内で呼ぶと件数比例の N+1 になり、
     * 設計書 {@code F00_content_visibility_resolver.md} のバッチ SQL 本数上限に反するため、
     * {@code SurveyTargetRepository#findTargetedSurveyIds} と対をなす一括版を用意する。</p>
     *
     * @param surveyIds 対象アンケート ID 集合（空を渡さないこと）
     * @param userId    閲覧者 user_id
     * @return {@code surveyIds} のうち当該ユーザーが結果閲覧者であるアンケート ID
     */
    @Query("""
            SELECT v.surveyId FROM SurveyResultViewerEntity v
            WHERE v.surveyId IN :surveyIds AND v.userId = :userId
            """)
    List<Long> findResultViewerSurveyIds(
            @Param("surveyIds") Collection<Long> surveyIds,
            @Param("userId") Long userId);

    /**
     * アンケートの結果閲覧者を全削除する。
     */
    void deleteBySurveyId(Long surveyId);
}
