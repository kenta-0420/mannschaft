package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.entity.ErrorReportOccurrenceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 Phase 2 — エラーレポート個別発生ログのリポジトリ。
 */
public interface ErrorReportOccurrenceRepository
        extends JpaRepository<ErrorReportOccurrenceEntity, Long> {

    /**
     * 指定エラーレポートの発生履歴を新しい順にページング取得する。
     */
    Page<ErrorReportOccurrenceEntity> findByErrorReportIdOrderByOccurredAtDesc(
            Long errorReportId, Pageable pageable);

    /**
     * GDPR エクスポート用: ユーザーIDで発生ログを取得する。
     */
    List<ErrorReportOccurrenceEntity> findByUserIdOrderByOccurredAtDesc(Long userId);

    /**
     * クリーンアップ用: 指定日時より古い発生ログを物理削除する。
     */
    @Modifying
    @Query("DELETE FROM ErrorReportOccurrenceEntity o WHERE o.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 退会処理用: ユーザーIDに紐づく発生ログの ip_address / user_agent を NULL 化する。
     * P2-F で AccountPurgeService から呼び出す予定。
     */
    @Modifying
    @Query("UPDATE ErrorReportOccurrenceEntity o "
            + "SET o.ipAddress = NULL, o.userAgent = NULL "
            + "WHERE o.userId = :userId")
    int anonymizeByUserId(@Param("userId") Long userId);

    /**
     * 退会済みユーザーの ip_address / user_agent を匿名化する（孤児補正バッチ用）。
     *
     * <p>{@code user_id} FK は V62.015 で撤廃済みのため、物理削除後も {@code user_id}
     * に元の値が残る。よって孤児は「{@code user_id} が指す {@code users} レコードが
     * 存在しない かつ {@code ip_address} または {@code user_agent} が非 NULL」で検出する。</p>
     *
     * <p>{@link com.mannschaft.app.errorreport.event.ErrorReportPurgeEventListener} が
     * 失敗した場合の補正用。Phase D-7 補正バッチ
     * ({@link com.mannschaft.app.errorreport.batch.ErrorReportPurgeBackfillBatchService})
     * から呼び出す。</p>
     *
     * @return 匿名化した行数
     */
    @Modifying
    @Query(value = """
            UPDATE error_report_occurrences ero
            LEFT JOIN users u ON ero.user_id = u.id
            SET ero.ip_address = NULL, ero.user_agent = NULL
            WHERE ero.user_id IS NOT NULL
              AND u.id IS NULL
              AND (ero.ip_address IS NOT NULL OR ero.user_agent IS NOT NULL)
            """, nativeQuery = true)
    int anonymizeOrphanByUserId();
}
