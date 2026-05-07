package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F12.5 Phase 2 — エラーレポート AI 分析履歴のリポジトリ。
 */
public interface ErrorReportAiAnalysisRepository
        extends JpaRepository<ErrorReportAiAnalysisEntity, Long> {

    /**
     * 指定エラーレポートの AI 分析履歴を新しい順にページング取得する。
     */
    Page<ErrorReportAiAnalysisEntity> findByErrorReportIdOrderByCreatedAtDesc(
            Long errorReportId, Pageable pageable);

    /**
     * 最新の SUCCESS 分析を取得する（管理画面の「現在の分析結果」表示用）。
     */
    Optional<ErrorReportAiAnalysisEntity>
            findFirstByErrorReportIdAndStatusOrderByCreatedAtDesc(
                    Long errorReportId, String status);

    /**
     * クリーンアップ用: 30日経過した raw_response を NULL 化する。
     */
    @Modifying
    @Query("UPDATE ErrorReportAiAnalysisEntity a "
            + "SET a.rawResponse = NULL "
            + "WHERE a.createdAt < :cutoff AND a.rawResponse IS NOT NULL")
    int updateRawResponseToNullByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * F12.5 Phase 2-E — Kanban カードに「AI 分析あり」バッジを描画するための判定用。
     * 指定 errorReportId のうち、SUCCESS な分析が 1 件以上存在するものを返す。
     */
    @Query("SELECT DISTINCT a.errorReportId FROM ErrorReportAiAnalysisEntity a "
            + "WHERE a.errorReportId IN :ids AND a.status = 'SUCCESS'")
    List<Long> findIdsHavingSuccessfulAnalysis(@Param("ids") List<Long> ids);

    /**
     * F12.5 Phase 2-F — AI ヘルスモニタ用。
     * 指定ステータス（"FAILED" 等）かつ {@code created_at} が {@code since} より後の件数を返す。
     */
    long countByStatusAndCreatedAtAfter(String status, LocalDateTime since);
}
