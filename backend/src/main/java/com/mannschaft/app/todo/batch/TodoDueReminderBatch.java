package com.mannschaft.app.todo.batch;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TODO の期限に関するリマインダー通知を送信するバッチ（F04.3 連携）。
 *
 * <p>毎朝 08:00（JST）に実行し、以下の2種の通知を担当者全員へ配信する:
 * <ol>
 *   <li>{@code TODO_DUE_TOMORROW} — 明日が期限の未完了 TODO（priority=NORMAL）</li>
 *   <li>{@code TODO_OVERDUE} — 期限超過の未完了 TODO（priority=HIGH、同日中の重複送信を防止）</li>
 * </ol>
 * </p>
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

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository todoAssigneeRepository;
    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;
    private final NotificationRepository notificationRepository;

    /**
     * 毎朝 08:00（JST）に期限リマインダー通知を送信する。
     *
     * <p>処理は以下の2段階で行う:
     * <ol>
     *   <li>明日期限の未完了・非ロック TODO → {@code TODO_DUE_TOMORROW}（NORMAL）</li>
     *   <li>期限超過の未完了・非ロック TODO → {@code TODO_OVERDUE}（HIGH、同日重複防止）</li>
     * </ol>
     * </p>
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tokyo")
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
                notificationDispatchService.dispatch(entity);
            } catch (RuntimeException ex) {
                log.error("TODO 期限通知送信失敗: userId={}, todoId={}, type={}",
                        userId, todo.getId(), notificationType, ex);
            }
        }
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
