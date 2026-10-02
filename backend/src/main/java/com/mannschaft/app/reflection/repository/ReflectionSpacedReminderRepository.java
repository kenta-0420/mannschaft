package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@link ReflectionSpacedReminderEntity} のリポジトリ（F06.5・§2.5 / §5.2）。
 */
@Repository
public interface ReflectionSpacedReminderRepository
        extends JpaRepository<ReflectionSpacedReminderEntity, UUID> {

    /** バッチ走査: due（remind_at <= now）かつ未送信（status=PENDING）のリマインダー（idx_..._due 利用）。 */
    List<ReflectionSpacedReminderEntity> findByStatusAndRemindAtLessThanEqual(
            ReflectionReminderStatus status, LocalDateTime now);

    /**
     * カレンダー想起予定印用（F06.5・§6.2 / AC-14）。指定ユーザーの特定 status かつ
     * remind_at が from..to 内のリマインダーを引く（PENDING を想定）。
     */
    List<ReflectionSpacedReminderEntity> findByUserIdAndStatusAndRemindAtBetween(
            Long userId, ReflectionReminderStatus status, LocalDateTime from, LocalDateTime to);

    /** PENDING リマインダー総数上限（§2.5.1 (a)・1,000）判定用。 */
    long countByUserIdAndStatus(Long userId, ReflectionReminderStatus status);

    /** エントリ削除・復活時に当該エントリ由来の特定 status の行を引く（CANCELLED 化・再生成用）。 */
    List<ReflectionSpacedReminderEntity> findByEntryIdAndStatus(UUID entryId, ReflectionReminderStatus status);

    /** exam_date 変更・テーマ削除時に PRE_EXAM 行を引く（CANCELLED 化・再生成用）。 */
    List<ReflectionSpacedReminderEntity> findByThemeIdAndStatus(UUID themeId, ReflectionReminderStatus status);
}
