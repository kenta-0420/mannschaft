package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ReminderKind;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.UpdateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.schedule.event.ReminderNotificationEvent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * リマインダー管理サービス。リマインダーの作成・一覧取得・即時リマインド・バッチ処理を担当する。
 *
 * <p>機能55 第二陣でリマインダーの相対指定（開始N分前）／絶対指定（固定日時）の両対応と、
 * 通知の実配線（{@link ReminderNotificationEvent} 発火 → IN_APP + PUSH）を行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleReminderService {

    private static final int MAX_REMINDERS_PER_SCHEDULE = 5;
    private static final String SOURCE_TYPE_SCHEDULE = "SCHEDULE";
    /** リマインダー保存時に OffsetDateTime を変換する先のタイムゾーン（JVM TZ と一致）。 */
    private static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Tokyo");

    private final ScheduleAttendanceReminderRepository reminderRepository;
    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    /**
     * {@link #processDueReminder} を {@code REQUIRES_NEW} で独立トランザクション実行するための自己参照。
     * 同一クラス内の self-invocation は Spring AOP プロキシを経由せず {@code @Transactional} が
     * 無効化されるため、{@code @Lazy} 注入したプロキシ経由で呼び出す。
     */
    @Lazy
    @Autowired
    private ScheduleReminderService self;

    /**
     * リマインダーを作成する。最大5件まで。
     *
     * <p>機能55 第二陣: {@link CreateReminderRequest#effectiveKind()} に応じて、ABSOLUTE は
     * {@code remindAt} を、RELATIVE は {@code remindBeforeMinutes} を保存する。RELATIVE 時の
     * {@code remindAt} は NULL のまま保持し、バッチが親予定の開始時刻から実効時刻を解決する。</p>
     *
     * @param scheduleId スケジュールID
     * @param requests   リマインダー作成リクエストリスト
     * @return 作成されたリマインダー一覧
     */
    @Transactional
    public List<ReminderResponse> createReminders(Long scheduleId, List<CreateReminderRequest> requests) {
        long existingCount = reminderRepository.countByScheduleId(scheduleId);
        if (existingCount + requests.size() > MAX_REMINDERS_PER_SCHEDULE) {
            throw new BusinessException(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        List<ReminderResponse> responses = requests.stream()
                .map(req -> {
                    ReminderKind kind = req.effectiveKind();
                    // OffsetDateTime → JSTのLocalDateTimeに変換して保存（バッチ側はLocalDateTime.now()=JSTと比較）
                    LocalDateTime remindAtJst = (kind == ReminderKind.ABSOLUTE && req.getRemindAt() != null)
                            ? req.getRemindAt().atZoneSameInstant(STORAGE_ZONE).toLocalDateTime()
                            : null;
                    ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                            .scheduleId(scheduleId)
                            .reminderKind(kind)
                            .remindAt(remindAtJst)
                            .remindBeforeMinutes(kind == ReminderKind.RELATIVE ? req.getRemindBeforeMinutes() : null)
                            .build();

                    reminder = reminderRepository.save(reminder);
                    return toReminderResponse(reminder);
                })
                .toList();

        log.info("リマインダー作成: scheduleId={}, 件数={}", scheduleId, requests.size());
        return responses;
    }

    /**
     * リマインダーを更新する（差し替え）。既存を全削除して新規リストを登録する（機能55 BE対応）。
     *
     * <p>空リストを渡すと全削除のみ（再登録なし）となる。
     * null は呼び出し元で「変更なし」として処理するため、このメソッドには渡さない。
     * 編集コンテキストのため {@link UpdateReminderRequest} を受け取り、過去日時の絶対指定も許容する。</p>
     *
     * @param scheduleId スケジュールID
     * @param requests   新規リマインダーリスト（空リスト可。null 禁止）
     */
    @Transactional
    public void updateReminders(Long scheduleId, List<UpdateReminderRequest> requests) {
        // 先に既存をすべて削除
        reminderRepository.deleteByScheduleId(scheduleId);

        // 空リストなら削除のみで終了
        if (requests.isEmpty()) {
            log.info("リマインダー全削除: scheduleId={}", scheduleId);
            return;
        }

        // 上限チェック
        if (requests.size() > MAX_REMINDERS_PER_SCHEDULE) {
            throw new BusinessException(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        // 新規登録（編集コンテキストのため過去日時も許容して直接エンティティ保存）
        requests.forEach(req -> {
            ReminderKind kind = req.effectiveKind();
            // OffsetDateTime → JSTのLocalDateTimeに変換して保存
            LocalDateTime remindAtJst = (kind == ReminderKind.ABSOLUTE && req.getRemindAt() != null)
                    ? req.getRemindAt().atZoneSameInstant(STORAGE_ZONE).toLocalDateTime()
                    : null;
            ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(kind)
                    .remindAt(remindAtJst)
                    .remindBeforeMinutes(kind == ReminderKind.RELATIVE ? req.getRemindBeforeMinutes() : null)
                    .build();
            reminderRepository.save(reminder);
        });

        log.info("リマインダー更新完了: scheduleId={}, 件数={}", scheduleId, requests.size());
    }

    /**
     * スケジュールに紐付くリマインダー一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return リマインダー一覧
     */
    public List<ReminderResponse> getReminders(Long scheduleId) {
        return reminderRepository.findByScheduleIdOrderByRemindAtAsc(scheduleId).stream()
                .map(this::toReminderResponse)
                .toList();
    }

    /**
     * 即時リマインドを送信する。
     *
     * <p>出欠回答が必要な予定は未回答者（UNDECIDED）へ、出欠不要の予定は全出欠対象者へ
     * リマインド通知イベントを発行する。{@link ReminderNotificationEvent} を介して
     * IN_APP + PUSH を配信する。</p>
     *
     * @param scheduleId スケジュールID
     */
    @Transactional
    public void sendReminder(Long scheduleId) {
        ScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            log.warn("リマインド対象予定なし（削除済み等）: scheduleId={}", scheduleId);
            return;
        }

        List<Long> recipientUserIds = resolveRecipients(schedule);
        if (recipientUserIds.isEmpty()) {
            log.info("リマインド対象者なし: scheduleId={}", scheduleId);
            return;
        }

        Locale locale = LocaleContextHolder.getLocale();
        boolean attendanceRequired = Boolean.TRUE.equals(schedule.getAttendanceRequired());
        String title = messageSource.getMessage(
                "notification.schedule.reminder.title", null,
                "まもなく予定の時刻です", locale);
        String bodyKey = attendanceRequired
                ? "notification.schedule.reminder.body.unanswered"
                : "notification.schedule.reminder.body.upcoming";
        String defaultBody = attendanceRequired
                ? "出欠が未回答の予定があります: "
                : "まもなく予定が始まります: ";
        String body = messageSource.getMessage(bodyKey, null, defaultBody, locale) + schedule.getTitle();

        NotificationScopeType scopeType = resolveScopeType(schedule);
        Long scopeId = resolveScopeId(schedule);

        eventPublisher.publishEvent(new ReminderNotificationEvent(
                scheduleId, scopeType, scopeId, recipientUserIds,
                title, body, "/schedules/" + scheduleId));

        log.info("即時リマインド送信: scheduleId={}, 対象者数={}", scheduleId, recipientUserIds.size());
    }

    /** 1 ページで取得する未送信リマインダー件数の上限。 */
    static final int REMINDER_PAGE_SIZE = 200;

    /** 1 回のバッチ実行で処理するページ数の上限（暴走防止。{@code lockAtMostFor} 内に収める）。 */
    static final int REMINDER_MAX_PAGES_PER_RUN = 25;

    /**
     * バッチ処理用: 未送信かつ実効リマインド時刻を過ぎたリマインダーを処理する。
     *
     * <p>機能55 第二陣で実効時刻ベースに改修。ABSOLUTE は {@code remind_at <= now}、
     * RELATIVE は親予定の {@code start_at <= now + remind_before_minutes} を SQL 側（
     * {@link ScheduleAttendanceReminderRepository#findDuePage}）で判定し、due なものだけを
     * ID キーセットページングで取得する（全件ロード・アプリ側フィルタは行わない）。
     * ページ単位ではなく1件ごとに独立トランザクション（{@link #processDueReminder}）でコミットし、
     * 途中で失敗しても再実行時は未送信分から再開できる。1 件の送信失敗は握り潰さず記録した上で
     * 後続の処理を継続する。</p>
     *
     * <p>クラス既定の {@code @Transactional(readOnly = true)} を打ち消し {@code NEVER} で外側 TX
     * を張らない契約を明示する（{@link com.mannschaft.app.notification.fanout.NotificationFanoutWorker#poll}
     * 前例）。外側に readOnly TX が生きたまま {@code REQUIRES_NEW} を呼ぶと、最大 25 ページ×200件ぶんの
     * トランザクション中断・再開コストと DB コネクション占有（プール枯渇リスク）を招くため。
     * 呼び出し元（{@code @Scheduled} の {@code ScheduleReminderBatchService#runBatch} と
     * {@code @BatchEndpoint} 経由の {@code BatchEndpointRegistry#invoke}）はいずれも本メソッド呼び出し
     * までの経路に {@code @Transactional} を持たないため {@code NEVER} で問題ない。</p>
     */
    @Transactional(propagation = Propagation.NEVER)
    public void processScheduledReminders() {
        LocalDateTime now = LocalDateTime.now();
        long cursorId = 0L;
        int processed = 0;
        int failed = 0;

        for (int page = 0; page < REMINDER_MAX_PAGES_PER_RUN; page++) {
            List<ScheduleAttendanceReminderEntity> due = reminderRepository.findDuePage(
                    now, cursorId, PageRequest.of(0, REMINDER_PAGE_SIZE));
            if (due.isEmpty()) {
                break;
            }

            for (ScheduleAttendanceReminderEntity reminder : due) {
                try {
                    self.processDueReminder(reminder.getId());
                    processed++;
                } catch (Exception e) {
                    failed++;
                    log.error("リマインダー送信失敗: reminderId={}, scheduleId={}",
                            reminder.getId(), reminder.getScheduleId(), e);
                }
            }

            cursorId = due.get(due.size() - 1).getId();
            if (due.size() < REMINDER_PAGE_SIZE) {
                break; // 最終ページ
            }
        }

        if (processed > 0 || failed > 0) {
            log.info("バッチリマインダー処理完了: 処理件数={}, 失敗件数={}", processed, failed);
        }
    }

    /**
     * リマインダー 1 件を独立トランザクションで送信・送信済み化する。
     * 呼び出し元（{@link #processScheduledReminders}）は非トランザクションのため、
     * 本メソッドの失敗が他のリマインダーの処理・コミット済み分を巻き込まない。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void processDueReminder(Long reminderId) {
        ScheduleAttendanceReminderEntity reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null || Boolean.TRUE.equals(reminder.getIsSent())) {
            return; // 既に処理済み・削除済み
        }
        sendReminder(reminder.getScheduleId());
        reminder.markAsSent();
        reminderRepository.save(reminder);
    }

    // --- プライベートメソッド ---

    /**
     * リマインド対象ユーザーIDを解決する。
     * 出欠必須予定は未回答者（UNDECIDED）、出欠不要予定は全出欠対象者を返す。
     */
    private List<Long> resolveRecipients(ScheduleEntity schedule) {
        boolean attendanceRequired = Boolean.TRUE.equals(schedule.getAttendanceRequired());
        List<ScheduleAttendanceEntity> targets = attendanceRequired
                ? attendanceRepository.findByScheduleIdAndStatus(schedule.getId(), AttendanceStatus.UNDECIDED)
                : attendanceRepository.findByScheduleIdOrderByUserIdAsc(schedule.getId());
        return targets.stream()
                .map(ScheduleAttendanceEntity::getUserId)
                .distinct()
                .toList();
    }

    private NotificationScopeType resolveScopeType(ScheduleEntity schedule) {
        if (schedule.getOrganizationId() != null) {
            return NotificationScopeType.ORGANIZATION;
        }
        if (schedule.getTeamId() != null) {
            return NotificationScopeType.TEAM;
        }
        return NotificationScopeType.PERSONAL;
    }

    private Long resolveScopeId(ScheduleEntity schedule) {
        if (schedule.getOrganizationId() != null) {
            return schedule.getOrganizationId();
        }
        if (schedule.getTeamId() != null) {
            return schedule.getTeamId();
        }
        return schedule.getUserId();
    }

    /**
     * エンティティをリマインダーレスポンスDTOに変換する。
     */
    private ReminderResponse toReminderResponse(ScheduleAttendanceReminderEntity entity) {
        return ReminderResponse.builder()
                .id(entity.getId())
                .reminderKind(entity.getReminderKind() != null ? entity.getReminderKind().name() : null)
                .remindAt(entity.getRemindAt())
                .remindBeforeMinutes(entity.getRemindBeforeMinutes())
                .isSent(entity.getIsSent())
                .sentAt(entity.getSentAt())
                .build();
    }
}
