package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.UpdateSettingsRequest;
import com.mannschaft.app.quickmemo.dto.UserQuickMemoSettingsResponse;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.entity.UserQuickMemoSettingsEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.UserQuickMemoSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ユーザーごとのポイっとメモ設定サービス。
 * リマインドデフォルト設定の取得・更新と既存メモへの反映を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQuickMemoSettingsService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final UserQuickMemoSettingsRepository settingsRepository;
    private final QuickMemoRepository memoRepository;
    private final AuditLogService auditLogService;

    /**
     * ユーザー設定を取得する。存在しない場合はデフォルト設定を作成して返す。
     */
    public UserQuickMemoSettingsResponse getSettings(Long userId) {
        UserQuickMemoSettingsEntity settings = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));
        return UserQuickMemoSettingsResponse.from(settings);
    }

    /**
     * リマインド設定を更新する。
     * apply_to パラメータに応じて既存メモのリマインドスケジュールも再計算する。
     * SERIALIZABLE + Deadlock Retry で並行書き込みを安全に処理する。
     *
     * @param userId  ユーザーID
     * @param req     更新リクエスト
     * @param applyTo NEW_ONLY / UNSENT / ALL
     */
    @Retryable(
            retryFor = {DeadlockLoserDataAccessException.class, CannotAcquireLockException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 1000)
    )
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UserQuickMemoSettingsResponse updateSettings(Long userId, UpdateSettingsRequest req,
                                                         String applyTo) {
        UserQuickMemoSettingsEntity settings = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        // 設定値を更新
        UserQuickMemoSettingsEntity updated = settings.toBuilder()
                .reminderEnabled(req.reminderEnabled() != null ? req.reminderEnabled() : settings.getReminderEnabled())
                .defaultOffset1Days(req.defaultOffset1Days() != null ? req.defaultOffset1Days() : settings.getDefaultOffset1Days())
                .defaultTime1(req.defaultTime1() != null ? req.defaultTime1() : settings.getDefaultTime1())
                .defaultOffset2Days(req.defaultOffset2Days() != null ? req.defaultOffset2Days() : settings.getDefaultOffset2Days())
                .defaultTime2(req.defaultTime2() != null ? req.defaultTime2() : settings.getDefaultTime2())
                .defaultOffset3Days(req.defaultOffset3Days() != null ? req.defaultOffset3Days() : settings.getDefaultOffset3Days())
                .defaultTime3(req.defaultTime3() != null ? req.defaultTime3() : settings.getDefaultTime3())
                .build();

        UserQuickMemoSettingsEntity saved = settingsRepository.save(updated);

        // 既存メモへの反映
        if (!"NEW_ONLY".equals(applyTo) && Boolean.TRUE.equals(saved.getReminderEnabled())) {
            applyToExistingMemos(userId, saved, applyTo);
        }

        auditLogService.record("QUICK_MEMO_SETTINGS_UPDATED", userId, null, null, null,
                null, null, null,
                "{\"applyTo\":\"" + applyTo + "\"}");

        return UserQuickMemoSettingsResponse.from(saved);
    }

    /**
     * 既存メモのリマインドスケジュールを再計算する。
     * - 再計算後 scheduled_at < NOW() の枠はスキップ（過去日時の即時送信防止）
     * - reminder_N_sent_at はクリアしない（既送信リマインド再送信防止）
     */
    private void applyToExistingMemos(Long userId, UserQuickMemoSettingsEntity settings, String applyTo) {
        LocalDateTime now = LocalDateTime.now(JST);

        // UNSENT: 未送信の枠があるメモ / ALL: 全未整理メモ
        List<QuickMemoEntity> memos = memoRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, "UNSORTED", PageRequest.of(0, 10000))
                .getContent();

        for (QuickMemoEntity memo : memos) {
            if (!memo.isDeleted()) {
                recalculateReminders(memo, settings, now, applyTo);
            }
        }
        log.info("リマインド再計算: userId={}, memoCount={}, applyTo={}", userId, memos.size(), applyTo);
    }

    private void recalculateReminders(QuickMemoEntity memo, UserQuickMemoSettingsEntity settings,
                                       LocalDateTime now, String applyTo) {
        LocalDate baseDate = memo.getCreatedAt().toLocalDate();

        LocalDateTime newScheduled1 = calcScheduledAt(baseDate, settings.getDefaultOffset1Days(), settings.getDefaultTime1());
        LocalDateTime newScheduled2 = calcScheduledAt(baseDate, settings.getDefaultOffset2Days(), settings.getDefaultTime2());
        LocalDateTime newScheduled3 = calcScheduledAt(baseDate, settings.getDefaultOffset3Days(), settings.getDefaultTime3());

        QuickMemoEntity.QuickMemoEntityBuilder builder = memo.toBuilder();
        boolean changed = false;

        // 未送信の枠かつ未来の場合のみ更新
        if (shouldUpdateSlot(memo.getReminder1SentAt(), newScheduled1, now, applyTo)) {
            builder.reminder1ScheduledAt(newScheduled1);
            changed = true;
        }
        if (shouldUpdateSlot(memo.getReminder2SentAt(), newScheduled2, now, applyTo)) {
            builder.reminder2ScheduledAt(newScheduled2);
            changed = true;
        }
        if (shouldUpdateSlot(memo.getReminder3SentAt(), newScheduled3, now, applyTo)) {
            builder.reminder3ScheduledAt(newScheduled3);
            changed = true;
        }

        if (changed) {
            memoRepository.save(builder.build());
        }
    }

    private boolean shouldUpdateSlot(LocalDateTime sentAt, LocalDateTime newScheduled,
                                      LocalDateTime now, String applyTo) {
        if (newScheduled == null) return false;
        if (newScheduled.isBefore(now)) return false; // 過去日時はスキップ
        if ("UNSENT".equals(applyTo)) {
            return sentAt == null; // 未送信のみ
        }
        return sentAt == null; // ALL でも送信済みはクリアしない
    }

    private LocalDateTime calcScheduledAt(LocalDate baseDate, Integer offsetDays, LocalTime time) {
        if (offsetDays == null || time == null) return null;
        return baseDate.plusDays(offsetDays).atTime(time);
    }

    @Transactional
    private UserQuickMemoSettingsEntity createDefaultSettings(Long userId) {
        UserQuickMemoSettingsEntity defaults = UserQuickMemoSettingsEntity.builder()
                .userId(userId)
                .reminderEnabled(false)
                .build();
        return settingsRepository.save(defaults);
    }
}
