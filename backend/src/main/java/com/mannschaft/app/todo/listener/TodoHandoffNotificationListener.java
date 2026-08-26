package com.mannschaft.app.todo.listener;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.event.TodoHandoffEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;
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
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

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

        String actionUrl = buildActionUrl(event.getScopeType(), event.getScopeId(), event.getTodoId());
        NotificationScopeType scopeType = mapScope(event.getScopeType());

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(event.getToUserIds().stream().toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
            locales = Map.of();
        }

        for (Long toUserId : event.getToUserIds()) {
            if (toUserId == null) continue;
            // 自己 handoff（操作者自身が宛先）には通知を作成しない
            if (toUserId.equals(event.getFromUserId())) continue;

            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(toUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.todo.handoff.title", null, "TODOが渡されました", locale);
                String body = messageSource.getMessage(
                        "notification.todo.handoff.body",
                        new Object[]{fromName, safe(event.getTodoTitle()), safe(event.getStatusLabelName())},
                        fromName + "さんから「" + safe(event.getTodoTitle()) + "」を渡されました（ステータス: "
                                + safe(event.getStatusLabelName()) + "）",
                        locale);
                if (event.getMessage() != null && !event.getMessage().isBlank()) {
                    body = body + " 💬 " + event.getMessage();
                }
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
                // TeamService 経由で slug 解決（team Entity の直接参照を排除 / ドメイン境界遵守）
                String slug = teamService.getSlugById(scopeId);
                yield slug != null
                        ? "/teams/" + slug + "/todos/" + todoId
                        : "/todos/" + todoId;
            }
            case ORGANIZATION -> {
                // OrganizationService 経由で slug 解決（org Entity の直接参照を排除 / ドメイン境界遵守）
                String slug = organizationService.getSlugById(scopeId);
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
