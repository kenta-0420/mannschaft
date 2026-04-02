package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.3 退会リマインダー用リポジトリ。
 */
public interface WithdrawalReminderRepository extends JpaRepository<UserEntity, Long> {

    /**
     * 退会申請ユーザーのうち、リマインダー送信対象を取得する。
     * deleted_atがstart〜end、かつreminderSentAtがreminderCutoff以前（または未設定）。
     *
     * @param deletedAtStart   退会申請日時の開始
     * @param deletedAtEnd     退会申請日時の終了
     * @param reminderCutoff   リマインダー送信済み判定の閾値（これより後に送信済みならスキップ）
     */
    @Query(value = """
        SELECT * FROM users
        WHERE deleted_at BETWEEN :deletedAtStart AND :deletedAtEnd
          AND (reminder_sent_at IS NULL OR reminder_sent_at < :reminderCutoff)
          AND purged_at IS NULL
        """, nativeQuery = true)
    List<UserEntity> findUsersForReminder(
            @Param("deletedAtStart") LocalDateTime deletedAtStart,
            @Param("deletedAtEnd") LocalDateTime deletedAtEnd,
            @Param("reminderCutoff") LocalDateTime reminderCutoff);
}
