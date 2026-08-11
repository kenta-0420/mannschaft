package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.visibility.ScheduleCommentViewerFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F03.16 予定コメントの通知発火（設計書 §6）。
 *
 * <h2>{@code afterCommit} からのみ呼ばれる（原則5・§6.6）</h2>
 * <p>コメントの INSERT（schedule ドメイン）と通知の発火（notification ドメインへの越境）を
 * 分離するため、本クラスは {@code ScheduleCommentService} の {@code TransactionSynchronization}
 * の {@code afterCommit()} からのみ呼び出す。通知は best-effort — 1件失敗しても他の宛先へ継続し、
 * 握りつぶさずログに残す（{@code .catch(() => {})} 相当の沈黙は禁止）。</p>
 *
 * <h2>通知は必ず可視性でフィルタしてから送る（§6.3・最重要）</h2>
 * <p>参照経路（{@code ScheduleCommentAccessGuard} / {@code ScheduleCommentVisibilityResolver}）と
 * 通知経路が<b>同一の {@code canView} 呼び出し</b>を使うことが漏洩を構造的に塞ぐ担保である
 * （{@link ScheduleCommentViewerFilter} 経由・AC-12b/AC-18b が同じ結論になることを検証する）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCommentNotifier {

    private static final String MENTIONED_TYPE = "SCHEDULE_COMMENT_MENTIONED";
    private static final String REPLIED_TYPE = "SCHEDULE_COMMENT_REPLIED";
    private static final String SOURCE_TYPE = "SCHEDULE_COMMENT";
    private static final int EXCERPT_LENGTH = 100;

    private final NotificationService notificationService;
    private final ScheduleCommentViewerFilter viewerFilter;

    /**
     * メンション通知・返信通知を発火する（best-effort）。
     *
     * @param schedule          親予定
     * @param commentId         投稿されたコメント ID
     * @param actorId           投稿者（通知の {@code actorId}）
     * @param mentionedUserIds  リクエストの {@code mentionedUserIds}（本人含む可・重複可）
     * @param replyRecipientId  返信通知の宛先（トップレベル投稿者。トップレベル投稿や自己返信は {@code null}）
     * @param bodyExcerpt       本文冒頭抜粋（100 文字以内・切り詰め済み）
     *
     * <h2>{@code REQUIRES_NEW} が必須な理由</h2>
     * <p>本メソッドは {@code afterCommit()} コールバックから呼ばれる。{@code afterCommit()} は
     * 呼び出し元トランザクションの {@code doCommit() → triggerAfterCommit()} の途中で発火し、
     * {@code cleanupAfterCompletion()}（同期状態のクリア）は<b>まだ完了していない</b>。
     * この状態で {@code @Transactional(REQUIRED)} のメソッドを呼ぶと、既にコミット処理中で
     * まもなく破棄される外側の同期スコープへ<b>参加してしまい</b>、内部の
     * {@code notificationService.createNotification} が実際には独立してコミットされない
     * （実測: {@code NotificationEntity} は返るが DB には残らない）。
     * {@code REQUIRES_NEW} で外側を必ず一旦サスペンドし、独立した新規トランザクションで実行する。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(
            ScheduleEntity schedule,
            UUID commentId,
            Long actorId,
            Set<Long> mentionedUserIds,
            Long replyRecipientId,
            String bodyExcerpt) {
        // 自分自身へのメンション通知は送らない（§6.5）。
        Set<Long> candidates = new LinkedHashSet<>();
        if (mentionedUserIds != null) {
            for (Long id : mentionedUserIds) {
                if (id != null && !id.equals(actorId)) {
                    candidates.add(id);
                }
            }
        }

        Set<Long> mentionedVisible;
        try {
            mentionedVisible = candidates.isEmpty()
                    ? Set.of()
                    : viewerFilter.filterViewers(schedule, candidates);
        } catch (Exception e) {
            log.error("SCHEDULE_COMMENT メンション通知の可視性フィルタに失敗: scheduleId={}", schedule.getId(), e);
            mentionedVisible = Set.of();
        }

        for (Long recipient : mentionedVisible) {
            sendMentioned(schedule, commentId, actorId, recipient, bodyExcerpt);
        }

        // 同一コメントで「メンション」と「返信」の両方に該当するユーザーには1通のみ（メンション優先・AC-25）。
        if (replyRecipientId != null
                && !replyRecipientId.equals(actorId)
                && !candidates.contains(replyRecipientId)) {
            // 発火時点で再評価する（投稿時点の権限をキャッシュしない・§6.3）。
            boolean visible;
            try {
                Set<Long> filtered = viewerFilter.filterViewers(schedule, Set.of(replyRecipientId));
                visible = filtered.contains(replyRecipientId);
            } catch (Exception e) {
                log.error("SCHEDULE_COMMENT 返信通知の可視性再評価に失敗: scheduleId={}", schedule.getId(), e);
                visible = false;
            }
            if (visible) {
                sendReplied(schedule, commentId, actorId, replyRecipientId, bodyExcerpt);
            }
        }
    }

    private void sendMentioned(ScheduleEntity schedule, UUID commentId, Long actorId, Long recipientId, String excerpt) {
        try {
            notificationService.createNotification(
                    recipientId,
                    MENTIONED_TYPE,
                    NotificationPriority.NORMAL,
                    "コメントであなたがメンションされました",
                    excerpt,
                    SOURCE_TYPE,
                    null,
                    scopeTypeOf(schedule),
                    scopeIdOf(schedule),
                    actionUrl(schedule, commentId),
                    actorId);
        } catch (Exception e) {
            log.error("SCHEDULE_COMMENT_MENTIONED 通知の作成に失敗: scheduleId={}, recipientId={}",
                    schedule.getId(), recipientId, e);
        }
    }

    private void sendReplied(ScheduleEntity schedule, UUID commentId, Long actorId, Long recipientId, String excerpt) {
        try {
            notificationService.createNotification(
                    recipientId,
                    REPLIED_TYPE,
                    NotificationPriority.NORMAL,
                    "あなたのコメントに返信がありました",
                    excerpt,
                    SOURCE_TYPE,
                    null,
                    scopeTypeOf(schedule),
                    scopeIdOf(schedule),
                    actionUrl(schedule, commentId),
                    actorId);
        } catch (Exception e) {
            log.error("SCHEDULE_COMMENT_REPLIED 通知の作成に失敗: scheduleId={}, recipientId={}",
                    schedule.getId(), recipientId, e);
        }
    }

    private String actionUrl(ScheduleEntity schedule, UUID commentId) {
        return "/calendar?scheduleId=" + schedule.getId() + "&commentId=" + commentId;
    }

    private NotificationScopeType scopeTypeOf(ScheduleEntity schedule) {
        return "ORGANIZATION".equals(ScheduleCommentViewerFilter.scopeTypeOf(schedule))
                ? NotificationScopeType.ORGANIZATION
                : NotificationScopeType.TEAM;
    }

    private Long scopeIdOf(ScheduleEntity schedule) {
        return ScheduleCommentViewerFilter.scopeIdOf(schedule);
    }

    /** 本文冒頭を100文字以内へ切り詰める（通知本文の上限・§6.3）。 */
    public static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= EXCERPT_LENGTH ? body : body.substring(0, EXCERPT_LENGTH);
    }
}
