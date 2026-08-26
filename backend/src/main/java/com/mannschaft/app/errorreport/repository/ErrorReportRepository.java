package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
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
 * F12.5 エラーレポートリポジトリ。
 */
public interface ErrorReportRepository extends JpaRepository<ErrorReportEntity, Long> {

    /**
     * error_hash でエラーレポートを検索する（statusに関わらず）。
     */
    Optional<ErrorReportEntity> findByErrorHash(String errorHash);

    /**
     * 同一エラーの原子的更新（重複集約）。
     * occurrence_count をインクリメントし、発生回数に応じて severity を自動昇格する。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE error_reports
        SET occurrence_count = occurrence_count + 1,
            last_occurred_at = :occurredAt,
            latest_user_comment = COALESCE(:userComment, latest_user_comment),
            severity = CASE
                WHEN occurrence_count + 1 >= 50 THEN 'CRITICAL'
                WHEN occurrence_count + 1 >= 10 THEN 'HIGH'
                ELSE severity
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE error_hash = :hash AND status IN ('NEW', 'INVESTIGATING', 'REOPENED')
        """, nativeQuery = true)
    int incrementOccurrence(@Param("hash") String hash,
                            @Param("occurredAt") LocalDateTime occurredAt,
                            @Param("userComment") String userComment);

    /**
     * user_id から organization_id をルックアップする。
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以後、
     * 一般メンバーの在籍行は {@code memberships} にしか無く、{@code user_roles} を
     * 唯一の起点にすると一般メンバーの所属組織が解決できず、エラーレポートの
     * 組織紐付けが NULL 化する。解決経路を次の 3 本の {@code UNION} とする
     * （{@code UNION ALL} ではなく {@code UNION} で重複を畳む）。</p>
     * <ul>
     *   <li>{@code user_roles} のチーム所属 → ACTIVE な {@code team_org_memberships} 経由</li>
     *   <li>{@code memberships} の TEAM 在籍（{@code left_at IS NULL}）→ 同経由</li>
     *   <li>{@code memberships} の ORGANIZATION 在籍（{@code left_at IS NULL}）＝組織直属</li>
     * </ul>
     *
     * <p>{@code memberships} 側の枝は索引 {@code (scope_type, scope_id, left_at)} /
     * {@code (user_id, left_at)} に載せるため、必ず {@code scope_type} の等値条件を伴う。</p>
     */
    @Query(value = """
        SELECT cand.organization_id
        FROM (
            SELECT tom.organization_id AS organization_id
              FROM team_org_memberships tom
              JOIN user_roles ur ON ur.team_id = tom.team_id
              WHERE ur.user_id = :userId AND tom.status = 'ACTIVE'
            UNION
            SELECT tom2.organization_id AS organization_id
              FROM team_org_memberships tom2
              JOIN memberships ms ON ms.scope_type = 'TEAM' AND ms.scope_id = tom2.team_id
              WHERE ms.user_id = :userId AND ms.left_at IS NULL AND tom2.status = 'ACTIVE'
            UNION
            SELECT ms2.scope_id AS organization_id
              FROM memberships ms2
              WHERE ms2.user_id = :userId AND ms2.scope_type = 'ORGANIZATION' AND ms2.left_at IS NULL
        ) cand
        LIMIT 1
        """, nativeQuery = true)
    Optional<Long> findOrganizationIdByUserId(@Param("userId") Long userId);

    /**
     * 管理者一覧用: ステータスと重要度でフィルタしてページング取得する。
     */
    Page<ErrorReportEntity> findByStatusAndSeverity(ErrorReportStatus status,
                                                     ErrorReportSeverity severity,
                                                     Pageable pageable);

    /**
     * 既知の不具合API用: 指定したステータス・重要度に該当するエラーレポートを取得する。
     */
    List<ErrorReportEntity> findByStatusInAndSeverityIn(List<ErrorReportStatus> statuses,
                                                         List<ErrorReportSeverity> severities);

    /**
     * 指定した重要度リスト・ステータスリストに該当するエラーレポートを取得する。
     */
    List<ErrorReportEntity> findBySeverityInAndStatusIn(List<ErrorReportSeverity> severities,
                                                         List<ErrorReportStatus> statuses);

    /**
     * ステータスごとの件数を取得する。
     */
    long countByStatus(ErrorReportStatus status);

    /**
     * 作成日時が指定日時より後のレポート件数を取得する。
     */
    long countByCreatedAtAfter(LocalDateTime dateTime);

    /**
     * 指定ステータスリストに該当するレポート件数を取得する。
     */
    long countByStatusIn(List<ErrorReportStatus> statuses);

    /**
     * 指定ステータスかつ更新日時が指定日時より後のレポート件数を取得する。
     */
    long countByStatusAndUpdatedAtAfter(ErrorReportStatus status, LocalDateTime dateTime);

    /**
     * 指定重要度リストかつ作成日時が指定日時より後のレポート件数を取得する。
     */
    long countBySeverityInAndCreatedAtAfter(List<ErrorReportSeverity> severities, LocalDateTime dateTime);

    /**
     * 指定ステータスリストに該当するレポートを occurrence_count 降順で上位5件取得する。
     */
    List<ErrorReportEntity> findTop5ByStatusInOrderByOccurrenceCountDesc(List<ErrorReportStatus> statuses);

    /**
     * 指定ステータスリストかつ更新日時が指定日時より前のレポートを取得する。
     */
    List<ErrorReportEntity> findByStatusInAndUpdatedAtBefore(List<ErrorReportStatus> statuses, LocalDateTime dateTime);

    /**
     * 指定ステータスリストかつ最終発生日時が指定日時より前のレポートを取得する。
     */
    List<ErrorReportEntity> findByStatusInAndLastOccurredAtBefore(List<ErrorReportStatus> statuses, LocalDateTime dateTime);

    /**
     * 指定ステータスかつ更新日時が指定日時より前のレポートを取得する。
     */
    List<ErrorReportEntity> findByStatusAndUpdatedAtBefore(ErrorReportStatus status, LocalDateTime dateTime);

    /**
     * GDPR個人データエクスポート用: ユーザーIDでエラーレポートを検索する。
     * userId が null のレコードは Spring Data の仕様により含まれない。
     */
    List<ErrorReportEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 管理者一覧用: ステータスでフィルタしてページング取得する。
     */
    Page<ErrorReportEntity> findByStatus(ErrorReportStatus status, Pageable pageable);

    /**
     * 管理者一覧用: 重要度でフィルタしてページング取得する。
     */
    Page<ErrorReportEntity> findBySeverity(ErrorReportSeverity severity, Pageable pageable);

    /**
     * 管理者一覧用: 作成日時の範囲でフィルタしてページング取得する。
     */
    Page<ErrorReportEntity> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * F12.5 Phase 2 — AI 自動分析バッチ用。
     * {@code last_ai_analysis_at} が NULL かつ {@code created_at} が cutoff より前のレポートを
     * 古い順に取得する。
     */
    @Query("SELECT e FROM ErrorReportEntity e "
            + "WHERE e.lastAiAnalysisAt IS NULL AND e.createdAt < :cutoff "
            + "ORDER BY e.createdAt ASC")
    List<ErrorReportEntity> findByLastAiAnalysisAtIsNullAndCreatedAtBefore(
            @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * F12.5 Phase 2 — Kanban ビュー用。
     * status と workflow_stage（NULL も含む）でフィルタしてページング取得する。
     * stage が null の場合は workflow_stage IS NULL のレコードを返す。
     */
    @Query("SELECT e FROM ErrorReportEntity e "
            + "WHERE e.status = :status "
            + "AND (e.workflowStage = :stage OR (:stage IS NULL AND e.workflowStage IS NULL)) "
            + "ORDER BY e.lastOccurredAt DESC")
    Page<ErrorReportEntity> findByStatusAndWorkflowStage(
            @Param("status") ErrorReportStatus status,
            @Param("stage") ErrorReportWorkflowStage stage,
            Pageable pageable);

    /**
     * F12.5 Phase 2-E — Kanban の「未着手」カラム用。
     * 指定ステータスリストに該当し、かつ workflow_stage が NULL のレポートを
     * {@code last_occurred_at DESC} 順にページング取得する。
     */
    Page<ErrorReportEntity> findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(
            List<ErrorReportStatus> statuses, Pageable pageable);

    /**
     * F12.5 Phase 2-E — Kanban の特定ステージカラム用。
     * 指定 workflow_stage に該当するレポートを {@code last_occurred_at DESC} 順に
     * ページング取得する。
     */
    Page<ErrorReportEntity> findByWorkflowStageOrderByLastOccurredAtDesc(
            ErrorReportWorkflowStage stage, Pageable pageable);

    /**
     * F10.6 Phase 10-δ — SLA期限超過かつ未対応のレポートを取得する（アラートバッチ用）。
     */
    @Query("SELECT e FROM ErrorReportEntity e "
        + "WHERE e.slaDueAt IS NOT NULL "
        + "AND e.slaDueAt < :now "
        + "AND e.status IN :statuses "
        + "ORDER BY e.slaDueAt ASC")
    List<ErrorReportEntity> findOverdueReports(
        @Param("now") LocalDateTime now,
        @Param("statuses") List<ErrorReportStatus> statuses);

    /**
     * F10.6 Phase 10-δ — overdueOnly フィルタ用のページネーションクエリ。
     */
    @Query("SELECT e FROM ErrorReportEntity e "
        + "WHERE e.slaDueAt IS NOT NULL "
        + "AND e.slaDueAt < :now "
        + "AND e.status IN :statuses")
    Page<ErrorReportEntity> findOverdueByStatusIn(
        @Param("now") LocalDateTime now,
        @Param("statuses") List<ErrorReportStatus> statuses,
        Pageable pageable);

    /**
     * F10.6 Phase 10-δ — 指定重要度リスト・ステータスリストの件数を取得する（週次ダイジェスト / SLA超過カウント用）。
     */
    long countBySeverityInAndStatusIn(List<ErrorReportSeverity> severities,
                                       List<ErrorReportStatus> statuses);

    /**
     * F10.6 Phase 10-δ — SLA期限超過かつ未対応の件数（週次ダイジェスト用）。
     */
    long countBySlaDueAtBeforeAndStatusIn(LocalDateTime slaDueAt,
                                           List<ErrorReportStatus> statuses);
}
