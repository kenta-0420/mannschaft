package com.mannschaft.app.todo.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TODO の期限に関するリマインダー通知を送信するバッチ（F04.3 連携）。
 *
 * <p>1時間ごとに実行し、各ユーザーのタイムゾーン（{@code users.timezone}）を参照して
 * 「そのユーザーのローカル時刻が 08:00〜08:59」の場合のみ通知を配信する。
 * これにより非JSTユーザーにも正しいタイミングで通知が飛ぶ。</p>
 *
 * <p>以下の2種の通知を担当者全員へ配信する:
 * <ol>
 *   <li>{@code TODO_DUE_TOMORROW} — 明日が期限の未完了 TODO（priority=NORMAL）</li>
 *   <li>{@code TODO_OVERDUE} — 期限超過の未完了 TODO（priority=HIGH、同日中の重複送信を防止）</li>
 * </ol>
 * </p>
 *
 * <p><strong>TZ別送信タイミング:</strong>
 * 実行時刻は UTC で固定。各担当者の {@code users.timezone}（例: "America/New_York"）を
 * {@link UserRepository#findTimezoneById} で取得し、その TZ での現在時刻が 08:00 台のユーザーのみが
 * 通知対象となる。timezone が取得できない場合は JST（Asia/Tokyo）にフォールバックする。</p>
 *
 * <p><strong>ロック中 TODO の除外:</strong>
 * F02.7 設計書 §5.2「ロック中 TODO への通知抑制」に従い、{@code milestone_locked = TRUE} の
 * TODO は通知対象から除外する。除外は {@link TodoRepository#findDueTomorrowForReminder} /
 * {@link TodoRepository#findOverdueForReminder} のクエリ内で既に実現されているため、
 * 本バッチはそれらを呼び出すだけでロック中 TODO を自動的にスキップできる。</p>
 *
 * <p><strong>担当者不在時のフォールバック:</strong>
 * 担当者が一人も割り当てられていない TODO については、作成者（{@code created_by}）
 * に通知を送信する（{@link com.mannschaft.app.todo.event.MilestoneNotificationListener}
 * と同様の方針）。</p>
 *
 * <p><strong>重複送信防止:</strong>
 * {@code TODO_OVERDUE} は毎朝1回のみ送信することを想定し、当日 00:00 以降に同一ユーザー
 * × 同一 TODO へ既に {@code TODO_OVERDUE} を送っていればスキップする。判定は
 * {@link NotificationRepository#existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual}
 * を使う。{@code TODO_DUE_TOMORROW} は期限1日前の1回限りイベントのため重複判定は行わない。</p>
 *
 * <p>設計根拠: {@code docs/features/F02.3_todo_project.md} §期限リマインダーバッチ、
 * {@code docs/features/F02.7_todo_milestone_gate.md} §5.2、
 * {@code docs/features/F04.3_push_notification.md} TODO 系通知表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoDueReminderBatch {

    /** ソース種別（NotificationEntity.sourceType に格納） */
    private static final String SOURCE_TYPE_TODO = "TODO";

    /** 通知種別: 明日が期限 */
    private static final String NOTIFICATION_TYPE_DUE_TOMORROW = "TODO_DUE_TOMORROW";

    /** 通知種別: 期限超過 */
    private static final String NOTIFICATION_TYPE_OVERDUE = "TODO_OVERDUE";

    /** リマインダー通知を送信する現地時刻の時間帯（0〜23）。 */
    private static final int REMINDER_HOUR = 8;

    /** timezone 未取得時のフォールバックタイムゾーン。 */
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository todoAssigneeRepository;
    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;
    private final NotificationRepository notificationRepository;
    // TODO: ScheduleドメインとUserドメインをまたいでいる。将来はUserTimezoneQueryService等に分離予定
    private final UserRepository userRepository;

    /**
     * 1時間ごとにバッチを実行し、その実行時刻が「08:00〜08:59」のユーザーのみに通知する。
     *
     * <p>JSTのみ対象だった固定cron（毎朝08:00 JST）を廃止し、
     * 各ユーザーのtimezoneフィールドを参照することで全タイムゾーンのユーザーに対応する。</p>
     *
     * <p>処理は以下の2段階で行う:
     * <ol>
     *   <li>明日期限の未完了・非ロック TODO → {@code TODO_DUE_TOMORROW}（NORMAL）</li>
     *   <li>期限超過の未完了・非ロック TODO → {@code TODO_OVERDUE}（HIGH、同日重複防止）</li>
     * </ol>
     * </p>
     */
    @BatchEndpoint(name = "todo-due-reminder-hourly", description = "TODO の明日期限と期限超過リマインドをユーザーTZ別に毎時チェックして送信する")
    @Scheduled(fixedDelay = 3_600_000)
    @SchedulerLock(name = "todoDueReminderHourly", lockAtLeastFor = "PT50M", lockAtMostFor = "PT2H")
    public void run() {
        log.info("TodoDueReminderBatch 開始");
        int dueTomorrowCount = sendDueTomorrowReminders();
        int overdueCount = sendOverdueReminders();
        log.info("TodoDueReminderBatch 完了: dueTomorrow={}, overdue={}",
                dueTomorrowCount, overdueCount);
    }

    /**
     * 明日期限の未完了・非ロック TODO に対して {@code TODO_DUE_TOMORROW} 通知を送信する。
     *
     * <p>読み取り専用トランザクションで TODO 一覧を取得し、通知作成そのものは
     * {@link NotificationService#createNotification} 側の独立トランザクションに委ねる。</p>
     *
     * @return 通知対象となった TODO 件数
     */
    @Transactional(readOnly = true)
    public int sendDueTomorrowReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<TodoEntity> todos = todoRepository.findDueTomorrowForReminder(tomorrow);
        for (TodoEntity todo : todos) {
            notifyAssignees(todo, NOTIFICATION_TYPE_DUE_TOMORROW, NotificationPriority.NORMAL,
                    "TODO 期限通知", String.format("TODO『%s』の期限は明日です。", todo.getTitle()),
                    false /* 重複チェックなし */);
        }
        log.info("TODO_DUE_TOMORROW 対象: {}件", todos.size());
        return todos.size();
    }

    /**
     * 期限超過の未完了・非ロック TODO に対して {@code TODO_OVERDUE} 通知を送信する。
     *
     * <p>当日 00:00 以降に同ユーザー × 同 TODO へ既に TODO_OVERDUE を送信済みならスキップ
     * （毎朝1回ルール）。</p>
     *
     * @return 通知対象となった TODO 件数
     */
    @Transactional(readOnly = true)
    public int sendOverdueReminders() {
        LocalDate today = LocalDate.now();
        List<TodoEntity> todos = todoRepository.findOverdueForReminder(today);
        for (TodoEntity todo : todos) {
            notifyAssignees(todo, NOTIFICATION_TYPE_OVERDUE, NotificationPriority.HIGH,
                    "TODO 期限超過", String.format("TODO『%s』の期限が過ぎています。", todo.getTitle()),
                    true /* 当日重複チェックあり */);
        }
        log.info("TODO_OVERDUE 対象: {}件", todos.size());
        return todos.size();
    }

    /**
     * TODO の担当者全員（不在なら作成者）に通知を送信する。
     *
     * <p>各ユーザーの {@code users.timezone} を参照し、そのユーザーのローカル時刻が
     * {@value #REMINDER_HOUR}:00 台の場合のみ通知する（非JST ユーザー対応）。
     * timezone が取得できない場合は {@link #FALLBACK_ZONE}（Asia/Tokyo）にフォールバックする。</p>
     *
     * @param todo             対象 TODO
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            通知タイトル
     * @param body             通知本文
     * @param dedupSameDay     当日 00:00 以降の同種通知をスキップするか
     */
    private void notifyAssignees(TodoEntity todo, String notificationType,
                                 NotificationPriority priority,
                                 String title, String body, boolean dedupSameDay) {
        Set<Long> recipients = collectRecipients(todo);
        if (recipients.isEmpty()) {
            log.debug("通知送信先なし: todoId={}, type={}", todo.getId(), notificationType);
            return;
        }

        NotificationScopeType scopeType = resolveScopeType(todo.getScopeType());
        String actionUrl = buildActionUrl(todo);
        LocalDateTime startOfToday = LocalDate.now().atTime(LocalTime.MIN);

        for (Long userId : recipients) {
            // ユーザーのtimezoneを取得し、現地時刻が08:00台でなければスキップ
            if (!isReminderHourForUser(userId)) {
                log.debug("TZスキップ: userId={}, todoId={}, type={}", userId, todo.getId(), notificationType);
                continue;
            }

            if (dedupSameDay && notificationRepository
                    .existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
                            userId, notificationType, SOURCE_TYPE_TODO, todo.getId(), startOfToday)) {
                log.debug("重複スキップ: userId={}, todoId={}, type={}",
                        userId, todo.getId(), notificationType);
                continue;
            }
            try {
                NotificationEntity entity = notificationService.createNotification(
                        userId,
                        notificationType,
                        priority,
                        title,
                        body,
                        SOURCE_TYPE_TODO,
                        todo.getId(),
                        scopeType,
                        todo.getScopeId(),
                        actionUrl,
                        null /* actorId: システムトリガー */
                );
                if (entity == null) {
                    // F00 Phase F: visibility ガードで作成スキップされた場合は dispatch しない
                    log.debug("TODO 期限通知をスキップ (visibility deny): userId={}, todoId={}, type={}",
                            userId, todo.getId(), notificationType);
                    continue;
                }
                notificationDispatchService.dispatch(entity);
            } catch (RuntimeException ex) {
                log.error("TODO 期限通知送信失敗: userId={}, todoId={}, type={}",
                        userId, todo.getId(), notificationType, ex);
            }
        }
    }

    /**
     * ユーザーのタイムゾーンにおける現在時刻がリマインダー送信時間帯（{@value #REMINDER_HOUR}:00 台）かどうかを返す。
     *
     * <p>{@link UserRepository#findTimezoneById} でユーザーの timezone 文字列を取得し、
     * {@link ZonedDateTime} でその TZ の現在時刻を求めて時（hour）を比較する。
     * timezone が無効な値の場合は {@link #FALLBACK_ZONE} を使用する。</p>
     *
     * @param userId 対象ユーザーID
     * @return リマインダー送信時間帯なら true
     */
    private boolean isReminderHourForUser(Long userId) {
        String tzString = userRepository.findTimezoneById(userId).orElse(null);
        ZoneId userZone;
        if (tzString == null || tzString.isBlank()) {
            userZone = FALLBACK_ZONE;
        } else {
            try {
                userZone = ZoneId.of(tzString);
            } catch (Exception e) {
                log.warn("無効なtimezone: userId={}, timezone={}, フォールバック使用", userId, tzString);
                userZone = FALLBACK_ZONE;
            }
        }
        int currentHour = ZonedDateTime.now(userZone).getHour();
        return currentHour == REMINDER_HOUR;
    }

    /**
     * 通知送信先ユーザーを収集する。
     * 担当者が一人も居なければ作成者にフォールバックする。
     *
     * @param todo 対象 TODO
     * @return 送信先ユーザー ID 集合（重複排除・挿入順保持）
     */
    private Set<Long> collectRecipients(TodoEntity todo) {
        Set<Long> recipients = new LinkedHashSet<>();
        List<TodoAssigneeEntity> assignees = todoAssigneeRepository.findByTodoId(todo.getId());
        for (TodoAssigneeEntity a : assignees) {
            recipients.add(a.getUserId());
        }
        if (recipients.isEmpty() && todo.getCreatedBy() != null) {
            recipients.add(todo.getCreatedBy());
        }
        return recipients;
    }

    /**
     * TodoScopeType → NotificationScopeType の変換。
     */
    private NotificationScopeType resolveScopeType(TodoScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PERSONAL -> NotificationScopeType.PERSONAL;
        };
    }

    /**
     * 通知タップ時の遷移先 URL を組み立てる。
     *
     * <p>TODO 詳細の配置は F02.3 で { @code /todos/&#123;id&#125; } と定められている
     * ため、スコープに関わらず同一パスを返す。スコープ固有のプロジェクト画面への
     * 導線が必要になった場合は projectId / scopeType に応じた振り分けを追加する。</p>
     */
    private String buildActionUrl(TodoEntity todo) {
        return "/todos/" + todo.getId();
    }
}
