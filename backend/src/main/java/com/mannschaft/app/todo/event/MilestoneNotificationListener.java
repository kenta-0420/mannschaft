package com.mannschaft.app.todo.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * マイルストーンアンロック通知リスナー（F02.7 ↔ F04.3 連携）。
 *
 * <p>{@link MilestoneUnlockedEvent} を受信し、アンロックされたマイルストーン配下 TODO の
 * 担当者全員にプッシュ通知を発行する。担当者が一人もいない場合はプロジェクト作成者に
 * 送信する（設計書 §5.6 / §6.3 準拠）。</p>
 *
 * <p>通知種別:
 * <ul>
 *   <li>{@code MILESTONE_UNLOCKED} — priority=NORMAL。前マイルストーン達成による自動アンロック</li>
 *   <li>{@code MILESTONE_FORCE_UNLOCKED} — priority=HIGH。ADMIN による強制アンロック</li>
 * </ul>
 * NotificationDispatchService が WebSocket（STOMP /user/{userId}/queue/notifications）＋
 * PWA Push を担当するため、リスナー側は createNotification → dispatch を連結するのみ。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneNotificationListener {

    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;
    private final ProjectMilestoneRepository milestoneRepository;
    private final ProjectRepository projectRepository;
    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository todoAssigneeRepository;

    /**
     * マイルストーンアンロック時にプッシュ通知・WebSocket 配信を実行する。
     *
     * <p>送信先決定ルール:
     * <ol>
     *   <li>マイルストーン配下 TODO の担当者を全件収集</li>
     *   <li>担当者が一人も存在しない場合はプロジェクト作成者を送信先に採用</li>
     *   <li>マイルストーン自体が削除済み or プロジェクトが削除済みならスキップ</li>
     * </ol>
     * </p>
     *
     * <p>{@link Async} 指定により、元トランザクションの commit 後に非同期で実行される
     * （{@link com.mannschaft.app.config.AsyncConfig} により {@code @EnableAsync} 済み）。
     * 通知送信の遅延・失敗が TODO ステータス変更トランザクションに影響を与えないよう分離する。</p>
     *
     * @param event マイルストーンアンロックイベント
     */
    @Async
    @EventListener
    public void onMilestoneUnlocked(MilestoneUnlockedEvent event) {
        ProjectMilestoneEntity milestone = milestoneRepository.findById(event.milestoneId()).orElse(null);
        if (milestone == null) {
            log.warn("マイルストーン通知スキップ: マイルストーン見当たらず milestoneId={}", event.milestoneId());
            return;
        }

        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(event.projectId()).orElse(null);
        if (project == null) {
            log.warn("マイルストーン通知スキップ: プロジェクト削除済 projectId={}, milestoneId={}",
                    event.projectId(), event.milestoneId());
            return;
        }

        Set<Long> recipientUserIds = collectRecipients(milestone.getId(), project.getCreatedBy());
        if (recipientUserIds.isEmpty()) {
            log.info("マイルストーン通知送信先なし: milestoneId={}, projectId={}",
                    milestone.getId(), project.getId());
            return;
        }

        String notificationType = event.isForced() ? "MILESTONE_FORCE_UNLOCKED" : "MILESTONE_UNLOCKED";
        NotificationPriority priority = event.isForced() ? NotificationPriority.HIGH : NotificationPriority.NORMAL;
        String title = event.isForced()
                ? "マイルストーンが強制アンロックされました"
                : "マイルストーンがアンロックされました";
        String body = String.format("「%s」が操作可能になりました。タスクを進めましょう。",
                milestone.getTitle());

        NotificationScopeType scopeType = resolveScopeType(project.getScopeType());
        String actionUrl = buildActionUrl(project, milestone.getId());

        int dispatched = 0;
        for (Long userId : recipientUserIds) {
            try {
                NotificationEntity entity = notificationService.createNotification(
                        userId,
                        notificationType,
                        priority,
                        title,
                        body,
                        "MILESTONE",
                        milestone.getId(),
                        scopeType,
                        project.getScopeId(),
                        actionUrl,
                        null /* actorId: システムトリガー or forcedBy は event に含まれないため省略 */
                );
                notificationDispatchService.dispatch(entity);
                dispatched++;
            } catch (RuntimeException ex) {
                log.error("マイルストーン通知送信失敗: userId={}, milestoneId={}, type={}",
                        userId, milestone.getId(), notificationType, ex);
            }
        }
        log.info("マイルストーンアンロック通知: milestoneId={}, projectId={}, type={}, dispatched={}",
                milestone.getId(), project.getId(), notificationType, dispatched);
    }

    /**
     * 通知送信先ユーザーを収集する。
     * 配下 TODO の担当者を優先し、誰もいなければプロジェクト作成者にフォールバックする。
     *
     * @param milestoneId          対象マイルストーン ID
     * @param projectCreatedBy     プロジェクト作成者 ID（フォールバック先）
     * @return ユーザー ID 集合（重複排除済）
     */
    private Set<Long> collectRecipients(Long milestoneId, Long projectCreatedBy) {
        Set<Long> recipients = new HashSet<>();

        List<TodoEntity> todos = todoRepository.findByMilestoneIdAndDeletedAtIsNull(milestoneId);
        for (TodoEntity todo : todos) {
            List<TodoAssigneeEntity> assignees = todoAssigneeRepository.findByTodoId(todo.getId());
            for (TodoAssigneeEntity a : assignees) {
                recipients.add(a.getUserId());
            }
        }

        // 担当者不在時のフォールバック: プロジェクト作成者
        if (recipients.isEmpty() && projectCreatedBy != null) {
            recipients.add(projectCreatedBy);
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
     */
    private String buildActionUrl(ProjectEntity project, Long milestoneId) {
        return switch (project.getScopeType()) {
            case TEAM -> String.format("/teams/%d/projects/%d?milestone=%d",
                    project.getScopeId(), project.getId(), milestoneId);
            case ORGANIZATION -> String.format("/organizations/%d/projects/%d?milestone=%d",
                    project.getScopeId(), project.getId(), milestoneId);
            case PERSONAL -> String.format("/projects/%d?milestone=%d",
                    project.getId(), milestoneId);
        };
    }
}
