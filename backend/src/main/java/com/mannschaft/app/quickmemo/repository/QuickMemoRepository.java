package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ポイっとメモリポジトリ。
 */
public interface QuickMemoRepository extends JpaRepository<QuickMemoEntity, Long> {

    /**
     * IDとユーザーIDで論理削除されていないメモを悲観的書き込みロックで取得する。
     * 更新・削除・TODO昇格など排他制御が必要な操作に使用する。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM QuickMemoEntity m WHERE m.id = :id AND m.userId = :userId AND m.deletedAt IS NULL")
    Optional<QuickMemoEntity> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * IDとユーザーIDで論理削除されていないメモを取得する（ロックなし）。
     */
    @Query("SELECT m FROM QuickMemoEntity m WHERE m.id = :id AND m.userId = :userId AND m.deletedAt IS NULL")
    Optional<QuickMemoEntity> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * ユーザー・ステータス別のメモ一覧を取得する（論理削除除外、ページネーション）。
     */
    Page<QuickMemoEntity> findByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status, Pageable pageable);

    /**
     * ユーザーの論理削除されていないメモ一覧（全ステータス）を取得する。
     */
    Page<QuickMemoEntity> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    /**
     * ユーザーのゴミ箱（論理削除済み）メモ一覧を取得する。
     */
    Page<QuickMemoEntity> findByUserIdAndDeletedAtIsNotNull(Long userId, Pageable pageable);

    /**
     * タイトルまたは本文でキーワード検索する（LIKE エスケープ済みパターンを渡すこと）。
     */
    @Query("""
            SELECT m FROM QuickMemoEntity m
            WHERE m.userId = :userId
              AND m.deletedAt IS NULL
              AND (m.title LIKE :pattern ESCAPE '\\' OR m.body LIKE :pattern ESCAPE '\\')
            ORDER BY m.updatedAt DESC
            """)
    List<QuickMemoEntity> searchByKeyword(@Param("userId") Long userId, @Param("pattern") String pattern);

    /**
     * ユーザーの指定ステータスのメモが存在するかチェックする。
     */
    boolean existsByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status);

    /**
     * ユーザーの UNSORTED ステータスのメモ件数を取得する。
     */
    long countByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status);

    /**
     * 指定日時より前に論理削除されたメモを取得する（物理削除バッチ用）。
     */
    @Query("SELECT m FROM QuickMemoEntity m WHERE m.deletedAt IS NOT NULL AND m.deletedAt < :threshold")
    List<QuickMemoEntity> findExpiredDeletedMemos(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * リマインド対象のメモを取得する（30分バッチ用）。
     */
    @Query("""
            SELECT m FROM QuickMemoEntity m
            WHERE m.deletedAt IS NULL
              AND (
                  (m.reminder1ScheduledAt <= :now AND m.reminder1SentAt IS NULL)
               OR (m.reminder2ScheduledAt <= :now AND m.reminder2SentAt IS NULL)
               OR (m.reminder3ScheduledAt <= :now AND m.reminder3SentAt IS NULL)
              )
            """)
    List<QuickMemoEntity> findReminderTargets(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * リマインド送信済みを記録する（1枠目）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE QuickMemoEntity m SET m.reminder1SentAt = :sentAt WHERE m.id = :id")
    void markReminder1Sent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * リマインド送信済みを記録する（2枠目）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE QuickMemoEntity m SET m.reminder2SentAt = :sentAt WHERE m.id = :id")
    void markReminder2Sent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * リマインド送信済みを記録する（3枠目）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE QuickMemoEntity m SET m.reminder3SentAt = :sentAt WHERE m.id = :id")
    void markReminder3Sent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);
}
