package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * シフト希望提出リマインドバッチサービス。
 * 提出期限の 48h 前・24h 前に未提出メンバーへ通知を送信する。
 * TODO: Phase 4-1 で低提出率アラート（isLowSubmissionAlerted）を実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftPreferenceReminderBatchService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftRequestRepository requestRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;
    private final TeamShiftSettingsRepository teamShiftSettingsRepository;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    private final AccessControlService accessControlService;
    /** Issue #2715 CMP-055 ロットC-4: 受信者 locale に応じた通知本文の組み立て。 */
    private final MessageSource messageSource;

    /**
     * 手動リマインド二重起動防止用 Valkey ロックの設定値。
     *
     * <p>Phase 11 事後検分 fixup（2026-05-17）: cron バッチは {@code @SchedulerLock} で保護されているが、
     * 手動 API は無保護のため ADMIN 連打で重複通知のリスクがあった。SET NX EX で同一
     * {@code scheduleId} 内の 15 秒以内の連打を阻止する（実通知送信時間 ＋ ネットワーク往復時間より長く取る）。</p>
     */
    private static final String MANUAL_REMINDER_LOCK_KEY_PREFIX = "shift:manual-reminder:lock:";
    private static final Duration MANUAL_REMINDER_LOCK_TTL = Duration.ofSeconds(15);

    /**
     * 10 分ごとに実行。48h前・24h前リマインドを未提出メンバーに送信する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SHIFT_ENABLED",
            reason = "止まるのは提出リマインド通知のみで DB は一切書き換わらず、シフト機能を閉じている間は提出を促す意味自体が無い")
    // TODO: shiftドメインがroleドメイン（UserRoleRepository）とteamドメイン（TeamShiftSettingsRepository）をまたいでいる。将来はそれぞれのQueryService経由で分離予定。Phase1-E: 2026-05-09
    @BatchEndpoint(name = "shift-preference-reminder", description = "シフト希望提出 48h・24h 前のリマインドを 10 分毎に送信する")
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "shift_preference_reminder", lockAtMostFor = "15m", lockAtLeastFor = "2m")
    @Transactional
    public void processReminders() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        int sent48h = process48hReminders(now);
        int sent24h = process24hReminders(now);
        log.info("シフト希望リマインドバッチ完了: 48h送信={}, 24h送信={}", sent48h, sent24h);
    }

    private int process48hReminders(LocalDateTime now) {
        List<ShiftScheduleEntity> targets = scheduleRepository
                .findFor48hReminder(now, now.plusHours(48));
        int count = 0;
        for (ShiftScheduleEntity schedule : targets) {
            try {
                // チームのリマインド設定を確認し、48h が無効なら送信をスキップ
                TeamShiftSettingsEntity teamSettings = teamShiftSettingsRepository
                        .findByTeamId(schedule.getTeamId())
                        .orElse(null);
                if (teamSettings != null && !teamSettings.isReminder48hEnabled()) {
                    log.info("48hリマインド無効のためスキップ: teamId={}", schedule.getTeamId());
                    continue;
                }
                sendReminderToUnsubmittedMembers(schedule,
                        "SHIFT_REQUEST_REMINDER_48H",
                        "notification.shift.reminder48h.title",
                        "シフト希望の提出期限 48 時間前です",
                        "notification.shift.reminder48h.body",
                        "シフト「" + schedule.getTitle() + "」の提出期限が 48 時間以内です。まだ提出していない場合はお早めに。",
                        new Object[]{schedule.getTitle()});
                schedule.markReminderSent48h();
                scheduleRepository.save(schedule);
                count++;
            } catch (Exception e) {
                // フラグをセットせず次回バッチで再試行
                log.error("48h リマインド送信失敗（スキップ）: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
    }

    private int process24hReminders(LocalDateTime now) {
        List<ShiftScheduleEntity> targets = scheduleRepository
                .findFor24hReminder(now, now.plusHours(24));
        int count = 0;
        for (ShiftScheduleEntity schedule : targets) {
            try {
                // チームのリマインド設定を確認し、24h が無効なら送信をスキップ
                TeamShiftSettingsEntity teamSettings = teamShiftSettingsRepository
                        .findByTeamId(schedule.getTeamId())
                        .orElse(null);
                if (teamSettings != null && !teamSettings.isReminder24hEnabled()) {
                    log.info("24hリマインド無効のためスキップ: teamId={}", schedule.getTeamId());
                    continue;
                }
                sendReminderToUnsubmittedMembers(schedule,
                        "SHIFT_REQUEST_REMINDER",
                        "notification.shift.reminder24h.title",
                        "シフト希望の提出期限が明日までです",
                        "notification.shift.reminder24h.body",
                        "シフト「" + schedule.getTitle() + "」の提出期限は明日までです。まだ提出していない場合は今すぐご対応ください。",
                        new Object[]{schedule.getTitle()});
                schedule.markReminderSent();
                scheduleRepository.save(schedule);
                count++;
            } catch (Exception e) {
                log.error("24h リマインド送信失敗（スキップ）: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
    }

    /**
     * Issue #2715 CMP-055 ロットC-4: 受信者 locale に応じて件名・本文を組み立てる。
     * 受信者ごとの locale 一括解決（N+1 防止）は {@link NotificationHelper#notifyAllLocalized}
     * 内部の {@code UserLocaleCache} が担う。
     */
    private void sendReminderToUnsubmittedMembers(ShiftScheduleEntity schedule,
            String notificationType,
            String titleKey, String titleDefault,
            String bodyKey, String bodyDefault, Object[] bodyArgs) {
        List<Long> unsubmitted = resolveUnsubmittedUserIds(schedule);
        if (unsubmitted.isEmpty()) return;

        notificationHelper.notifyAllLocalized(
                unsubmitted, notificationType,
                "SHIFT_SCHEDULE", schedule.getId(),
                NotificationScopeType.TEAM, schedule.getTeamId(),
                "/shifts/schedules/" + schedule.getId(), null,
                (userId, locale) -> new NotificationHelper.LocalizedMessage(
                        messageSource.getMessage(titleKey, null, titleDefault, locale),
                        messageSource.getMessage(bodyKey, bodyArgs, bodyDefault, locale)));

        log.info("シフト希望リマインド送信: type={}, scheduleId={}, 未提出人数={}",
                notificationType, schedule.getId(), unsubmitted.size());
    }

    /**
     * シフト希望未提出メンバーの ID 一覧を解決する。
     *
     * <p>cron バッチと手動リマインド API の両方から再利用される。</p>
     */
    private List<Long> resolveUnsubmittedUserIds(ShiftScheduleEntity schedule) {
        Set<Long> submittedUserIds = requestRepository
                .findByScheduleIdOrderBySlotDateAsc(schedule.getId())
                .stream()
                .map(ShiftRequestEntity::getUserId)
                .collect(Collectors.toSet());

        // TODO: SUPPORTER・GUEST を除外するロール別フィルタは Phase 4-1 で実装
        return userRoleRepository
                .findUserIdsByScope("TEAM", schedule.getTeamId())
                .stream()
                .filter(uid -> !submittedUserIds.contains(uid))
                .toList();
    }

    /**
     * 管理者の手動操作によるリマインド送信。
     *
     * <p>COLLECTING ステータスのスケジュールにのみ実行可能。cron バッチと同じ
     * 「未提出者抽出 → 通知一斉送信」ロジックを再利用しつつ、監査ログを必ず残す。</p>
     *
     * @param scheduleId スケジュール ID
     * @param userId     呼び出し元（操作した管理者）の ID
     * @return 送信件数と対象ユーザー ID 一覧
     * <p>Phase 11 事後検分 fixup（2026-05-17）: ADMIN 連打による重複通知を阻止するため、
     * Valkey の SET NX EX で {@code scheduleId} 単位 15 秒のロックを取得する。同一 schedule の
     * 連続呼び出しは {@link ShiftErrorCode#MANUAL_REMINDER_THROTTLED} で短絡する。
     * cron バッチ側の {@code @SchedulerLock} とは独立の名前空間を使用するため、cron 走行中でも
     * 手動 API は別ロックとして競合しない（業務的にも cron と手動は別文脈）。</p>
     *
     * @throws BusinessException スケジュールが存在しない場合 ({@link ShiftErrorCode#SHIFT_SCHEDULE_NOT_FOUND}) /
     *                           当該チームの ADMIN/DEPUTY_ADMIN でも SYSTEM_ADMIN でもない場合（COMMON_002）/
     *                           COLLECTING 以外の場合 ({@link ShiftErrorCode#INVALID_SCHEDULE_STATUS}) /
     *                           15 秒以内に同一 scheduleId への連打があった場合 ({@link ShiftErrorCode#MANUAL_REMINDER_THROTTLED})
     */
    @Transactional
    public ManualRemindResponse triggerManualReminder(Long scheduleId, Long userId) {
        // Valkey ロック取得（SET NX EX）。失敗時は連打とみなして 429 相当で短絡。
        // 連打防止のため認可より先にロックを取得する（throttle-first 維持）。
        String lockKey = MANUAL_REMINDER_LOCK_KEY_PREFIX + scheduleId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                String.valueOf(userId),
                MANUAL_REMINDER_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("シフト希望手動リマインド連打検出: scheduleId={}, operator={}", scheduleId, userId);
            throw new BusinessException(ShiftErrorCode.MANUAL_REMINDER_THROTTLED);
        }

        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

        // per-scope 認可（Track2 第二陣 / 2026-05-29）:
        // コントローラーの @PreAuthorize("hasRole('ADMIN')") は per-scope 判定にならないため、
        // ここで「当該シフトが属するチームの ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN」を強制する。
        if (!accessControlService.isSystemAdmin(userId)) {
            accessControlService.checkAdminOrAbove(userId, schedule.getTeamId(), "TEAM");
        }

        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        List<Long> unsubmitted = resolveUnsubmittedUserIds(schedule);

        if (!unsubmitted.isEmpty()) {
            // Issue #2715 CMP-055 ロットC-4: 受信者 locale に応じて件名・本文を組み立てる
            // （locale 一括解決は notifyAllLocalized 内部の UserLocaleCache が担う）。
            notificationHelper.notifyAllLocalized(
                    unsubmitted,
                    "SHIFT_REQUEST_REMINDER_MANUAL",
                    "SHIFT_SCHEDULE", schedule.getId(),
                    NotificationScopeType.TEAM, schedule.getTeamId(),
                    "/shifts/schedules/" + schedule.getId(), null,
                    (recipientId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.shift.manualReminder.title", null,
                                    "シフト希望提出のリマインド", locale),
                            messageSource.getMessage(
                                    "notification.shift.manualReminder.body",
                                    new Object[]{schedule.getTitle()},
                                    "シフト「" + schedule.getTitle() + "」の希望提出のお願いです。提出期限までにご対応ください。",
                                    locale)));
        }

        // 監査ログ: MANUAL_REMINDER（操作者・スケジュール・チーム・送信件数を記録）
        auditLogService.record(
                AuditEventType.SHIFT_MANUAL_REMINDER_SENT.name(),
                userId, null, schedule.getTeamId(), null,
                null, null, null,
                "{\"schedule_id\":" + schedule.getId()
                        + ",\"reminded_count\":" + unsubmitted.size() + "}");

        log.info("シフト希望手動リマインド送信: scheduleId={}, 未提出人数={}, operator={}",
                schedule.getId(), unsubmitted.size(), userId);

        return ManualRemindResponse.builder()
                .scheduleId(schedule.getId())
                .remindedCount(unsubmitted.size())
                .remindedUserIds(unsubmitted)
                .build();
    }
}
