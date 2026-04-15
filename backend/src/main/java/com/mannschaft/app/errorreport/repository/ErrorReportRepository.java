package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
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
     * user_roles → team_org_memberships の結合で組織IDを取得する。
     */
    @Query(value = """
        SELECT DISTINCT tom.organization_id
        FROM team_org_memberships tom
        JOIN user_roles ur ON ur.team_id = tom.team_id
        WHERE ur.user_id = :userId AND tom.status = 'ACTIVE'
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
}
