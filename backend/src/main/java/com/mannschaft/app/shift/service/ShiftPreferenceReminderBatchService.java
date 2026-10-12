package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderKind;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderNotificationEvent;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <h2>通知のトランザクション境界（Issue #2997 / CMP-260827-1152 G6）</h2>
 * <p>定期バッチ {@link #processReminders} は<b>非トランザクションのオーケストレータ</b>で、スケジュール 1 件ごとに
 * {@link ShiftPreferenceReminderRunner#remindOne}（{@code REQUIRES_NEW}）で配送要求を publish する。
 * 実通知は {@code AFTER_COMMIT} の {@code ShiftPreferenceReminderNotificationListener} が行い、
 * 配送後に送信済みフラグを立てる（全員失敗ならフラグは立たず次回再送）。通知の失敗が他スケジュールの状態を
 * 巻き戻すことはない。バッチ実行の重なりは {@code @SchedulerLock} が防ぐ（旧実装と同じ）。手動リマインド {@link #triggerManualReminder} も
 * 業務TX内では publish のみを行う。</p>
 * TODO: Phase 4-1 で低提出率アラート（isLowSubmissionAlerted）を実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftPreferenceReminderBatchService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftRequestRepository requestRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamShiftSettingsRepository teamShiftSettingsRepository;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    /** 1 スケジュールぶんの項目TX（フラグ確定 + 配送要求の publish）。 */
    private final ShiftPreferenceReminderRunner reminderRunner;
    /** 手動リマインドの配送要求の publish 用（手動は呼び出し元TXの中で publish する）。 */
    private final ApplicationEventPublisher eventPublisher;

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
                if (reminderRunner.remindOne(schedule.getId(), ShiftPreferenceReminderKind.REMINDER_48H,
                        resolveUnsubmittedUserIds(schedule))) {
                    count++;
                }
            } catch (Exception e) {
                // 項目TXがロールバックされフラグは立たない。次回バッチで再試行（他スケジュールには影響しない）
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
                if (reminderRunner.remindOne(schedule.getId(), ShiftPreferenceReminderKind.REMINDER_24H,
                        resolveUnsubmittedUserIds(schedule))) {
                    count++;
                }
            } catch (Exception e) {
                log.error("24h リマインド送信失敗（スキップ）: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
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

        return userRoleRepository
                .findMemberCandidateIdsByTeam(schedule.getTeamId())
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
     * @throws BusinessException スケジュールが存在しない場合 ({@link ShiftErrorCode#SHIFT_SCHEDULE_NOT_FOUND) /
     *                           COLLECTING 以外の場合 ({@link ShiftErrorCode#INVALID_SCHEDULE_STATUS}) /
     *                           15 秒以内に同一 scheduleId への連打があった場合 ({@link ShiftErrorCode#MANUAL_REMINDER_THROTTLED})
     */
    @Transactional
    public ManualRemindResponse triggerManualReminder(Long scheduleId, Long userId) {
        // Valkey ロック取得（SET NX EX）。失敗時は連打とみなして 429 相当で短絡。
        // 認可（越境の 404 隠蔽・管理者判定）は呼び出し元の ShiftScheduleFacade が済ませているので、
        // ロックは認可の後に取る（CMP-260923-0954 W6a: 部外者が 15 秒ロックを取って管理者の手動リマインドを
        // 塞ぐ経路を作らない）。
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

        // per-scope 認可は ShiftScheduleFacade#remindUnsubmitted が tx の外で済ませている
        // （当該シフトが属するチームの ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN。越境は不在と同じ 404）。
        // ここは tx の中でスケジュールを読み直し、不在・論理削除済みなら 404 SHIFT_001（K1）。
        // 状態の判定は認可の後。remind は FOR UPDATE ではなく Valkey のロックで直列化する（是正前から同じ）。

        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }

        List<Long> unsubmitted = resolveUnsubmittedUserIds(schedule);

        if (!unsubmitted.isEmpty()) {
            // 業務TX内では配送要求の publish のみ。実通知は AFTER_COMMIT の
            // ShiftPreferenceReminderNotificationListener が行う（Issue #2997 G6）。
            eventPublisher.publishEvent(new ShiftPreferenceReminderNotificationEvent(
                    ShiftPreferenceReminderKind.MANUAL, schedule.getId(), schedule.getTeamId(),
                    schedule.getTitle(), List.copyOf(unsubmitted)));
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
