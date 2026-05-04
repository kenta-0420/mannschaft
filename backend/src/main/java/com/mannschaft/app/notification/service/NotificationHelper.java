package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 通知発火ヘルパー。各モジュールから通知を簡便に作成・配信するためのファサード。
 *
 * <p>使用例:</p>
 * <pre>
 * notificationHelper.notify(userId, "SCHEDULE_REMINDER", "リマインド", "出欠未回答です",
 *         "SCHEDULE", scheduleId, NotificationScopeType.TEAM, teamId,
 *         "/schedules/" + scheduleId, actorId);
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHelper {

    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;

    /**
     * F00 Phase F セキュリティガード (§11.1): 一括通知 ({@link #notifyAll})
     * の前段で受信者リストを {@link ContentVisibilityChecker#filterAccessible}
     * によりバッチで絞り込むために用いる。N+1 の {@code canView} ループを
     * 単一 batch クエリ 1 回で処理することで、大量受信者通知 (大会 / 回覧 /
     * 確認通知) における性能を担保しつつ漏れなくガードする。
     *
     * <p>fail-soft: ReferenceType 未対応の sourceType に対しては
     * 受信者リスト全件をそのまま通過させる (既存挙動の互換性確保)。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * 単一ユーザーに通知を作成・配信する。
     *
     * <p>F00 Phase F: {@code createNotification} が visibility deny で
     * {@code null} を返した場合、配信もスキップする。
     */
    public void notify(Long userId, String notificationType, String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, NotificationPriority.NORMAL,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        if (notification == null) {
            return;
        }
        dispatchService.dispatch(notification);
    }

    /**
     * 単一ユーザーに優先度指定で通知を作成・配信する。
     *
     * <p>F00 Phase F: {@code createNotification} が visibility deny で
     * {@code null} を返した場合、配信もスキップする。
     */
    public void notify(Long userId, String notificationType, NotificationPriority priority,
                       String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, priority,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        if (notification == null) {
            return;
        }
        dispatchService.dispatch(notification);
    }

    /**
     * 複数ユーザーに一括通知を作成・配信する。
     *
     * <p>F00 Phase F: 受信者リストを事前に
     * {@link ContentVisibilityChecker#filterAccessible} で絞り込み、
     * 閲覧不可ユーザーには通知を作らない (§11.1 受信者リスト確定後の
     * 必須フィルタ)。
     */
    public void notifyAll(List<Long> userIds, String notificationType, String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        List<Long> filtered = filterAccessibleRecipients(userIds, sourceType, sourceId);
        for (Long userId : filtered) {
            try {
                notify(userId, notificationType, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, userCount={}（visibility絞込後）", notificationType, filtered.size());
    }

    /**
     * 複数ユーザーに優先度指定で一括通知を作成・配信する。
     *
     * <p>F00 Phase F: 受信者リストを事前に
     * {@link ContentVisibilityChecker#filterAccessible} で絞り込み、
     * 閲覧不可ユーザーには通知を作らない (§11.1 受信者リスト確定後の
     * 必須フィルタ)。
     */
    public void notifyAll(List<Long> userIds, String notificationType, NotificationPriority priority,
                          String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        List<Long> filtered = filterAccessibleRecipients(userIds, sourceType, sourceId);
        for (Long userId : filtered) {
            try {
                notify(userId, notificationType, priority, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, priority={}, userCount={}（visibility絞込後）",
                notificationType, priority, filtered.size());
    }

    /**
     * 受信者リストを visibility ガードで絞り込む (F00 Phase F)。
     *
     * <p>fail-soft: {@code sourceType} が {@link ReferenceType} に
     * 解決できない、または {@code sourceId} が null の通知は判定対象外として
     * 入力をそのまま返す。Resolver 配備済の type に対しては
     * 各受信者ごとに {@code canView} で個別判定する。
     *
     * @param userIds    候補受信者リスト
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @return 閲覧可能と判定された受信者の絞込結果
     */
    private List<Long> filterAccessibleRecipients(List<Long> userIds, String sourceType, Long sourceId) {
        if (userIds == null || userIds.isEmpty() || sourceId == null) {
            return userIds;
        }
        Optional<ReferenceType> refType = NotificationSourceTypeMapper.resolve(sourceType);
        if (refType.isEmpty()) {
            return userIds;
        }
        // §7.1 現在の filterAccessible は (type, contentIds, userId) シグネチャで
        // 「単一 user に対する複数 content」のフィルタとなる。本ユースケースは
        // 「単一 content に対する複数 user」のため、各 user について canView を回す。
        // 将来 §11.1 §17 Q11 で API 拡張 (Resolver 側 batch by users) が入ったら
        // ここを置換する。
        ReferenceType type = refType.get();
        Long contentId = sourceId;
        return userIds.stream()
                .filter(uid -> {
                    boolean allowed = visibilityChecker.canView(type, contentId, uid);
                    if (!allowed) {
                        log.warn("通知受信者除外 (visibility deny): userId={}, refType={}, contentId={}",
                                uid, type, contentId);
                    }
                    return allowed;
                })
                .toList();
    }

    /**
     * テスト・デバッグ用に visibility 絞込関数を露出する。
     *
     * <p>本メソッドは {@link Set} を返さず {@link List} を返すことで
     * 元の挿入順序 (UI 上の通知順序の決定要因) を保つ。
     *
     * @param userIds    候補受信者リスト
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @return 閲覧可能と判定された受信者の絞込結果
     */
    public List<Long> filterAccessibleForTest(List<Long> userIds, String sourceType, Long sourceId) {
        return filterAccessibleRecipients(userIds, sourceType, sourceId);
    }
}
