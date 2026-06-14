package com.mannschaft.app.todo.listener;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.event.TodoHandoffEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Set;

/**
 * TODO キャッチボール通知リスナー（F02.3.1 Phase 2）。
 *
 * <p>{@link TodoHandoffEvent} を受け、操作者を除く各 toUserId に
 * {@code TODO_HANDED_OFF} 通知を作成する。自己 handoff（操作者自身が宛先）
 * の場合は通知を発火しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoHandoffNotificationListener {

    /** 通知種別文字列定数（NotificationEntity.notificationType に保存）。 */
    public static final String NOTIFICATION_TYPE_TODO_HANDED_OFF = "TODO_HANDED_OFF";

    private final NotificationService notificationService;
    private final NameResolverService nameResolverService;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * キャッチボールイベントを受信して通知を作成する。
     *
     * <p>{@link TransactionalEventListener} で {@link TransactionPhase#AFTER_COMMIT} を指定し、
     * 呼び出し元トランザクションが正常コミットされた後にのみ通知を発火する。これにより
     * トランザクションがロールバックされた場合に「処理は失敗したが通知だけ飛んだ」状態を防ぐ。</p>
     *
     * @param event 引き渡しイベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHandoff(TodoHandoffEvent event) {
        if (event.getToUserIds() == null || event.getToUserIds().isEmpty()) {
            return;
        }

        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(event.getFromUserId()));
        String fromName = nameMap.getOrDefault(event.getFromUserId(), "");
        String title = "TODOが渡されました";
        String body = String.format("%sさんから「%s」を渡されました（ステータス: %s）",
                fromName, safe(event.getTodoTitle()), safe(event.getStatusLabelName()));
        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            body = body + " 💬 " + event.getMessage();
        }

        String actionUrl = buildActionUrl(event.getScopeType(), event.getScopeId(), event.getTodoId());
        NotificationScopeType scopeType = mapScope(event.getScopeType());

        for (Long toUserId : event.getToUserIds()) {
            if (toUserId == null) continue;
            // 自己 handoff（操作者自身が宛先）には通知を作成しない
            if (toUserId.equals(event.getFromUserId())) continue;

            try {
                notificationService.createNotification(
                        toUserId,
                        NOTIFICATION_TYPE_TODO_HANDED_OFF,
                        NotificationPriority.NORMAL,
                        title,
                        body,
                        "TODO",
                        event.getTodoId(),
                        scopeType,
                        event.getScopeId(),
                        actionUrl,
                        event.getFromUserId()
                );
            } catch (Exception e) {
                // 通知失敗は本処理を止めない（ログのみ）
                log.warn("TODO キャッチボール通知の作成に失敗: toUserId={}, todoId={}, cause={}",
                        toUserId, event.getTodoId(), e.toString());
            }
        }
    }

    private NotificationScopeType mapScope(TodoScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PERSONAL -> NotificationScopeType.PERSONAL;
        };
    }

    /**
     * 通知タップ時のアクション URL を組み立てる。
     *
     * <p>TEAM / ORGANIZATION は slug ベースの URL（/teams/{slug}/todos/{todoId}）を生成する。
     * slug が取得できない場合（チーム/組織が論理削除済み等）は /todos/{todoId} にフォールバックする。</p>
     *
     * @param scopeType TODO のスコープ種別
     * @param scopeId   TODO のスコープ数値 ID
     * @param todoId    TODO ID
     * @return 遷移先 URL 文字列
     */
    private String buildActionUrl(TodoScopeType scopeType, Long scopeId, Long todoId) {
        return switch (scopeType) {
            case TEAM -> {
                String slug = teamRepository.findById(scopeId)
                        .map(t -> t.getSlug())
                        .orElse(null);
                yield slug != null
                        ? "/teams/" + slug + "/todos/" + todoId
                        : "/todos/" + todoId;
            }
            case ORGANIZATION -> {
                String slug = organizationRepository.findById(scopeId)
                        .map(o -> o.getSlug())
                        .orElse(null);
                yield slug != null
                        ? "/organizations/" + slug + "/todos/" + todoId
                        : "/todos/" + todoId;
            }
            case PERSONAL -> "/todos/" + todoId;
        };
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
