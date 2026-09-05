package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleCommentPostedEvent;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.visibility.ScheduleCommentViewerFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F03.16 予定コメントの通知配送リスナー（設計書 §6 / Issue #2990 L8）。
 *
 * <h2>{@code AFTER_COMMIT} 境界の内側でのみ動く（原則5・§6.6）</h2>
 * <p>コメントの INSERT（schedule ドメイン）と通知の発火（notification ドメインへの越境）を
 * 分離するため、本クラスは {@link ScheduleCommentPostedEvent} を
 * {@code @TransactionalEventListener(AFTER_COMMIT)} で受け取る。通知は best-effort —
 * 1件失敗しても他の宛先へ継続し、握りつぶさずログに残す（{@code .catch(() => {})} 相当の沈黙は禁止）。</p>
 *
 * <h2>是正前との差（Issue #2990 L8）— 挙動は変えず、境界を宣言的にした</h2>
 * <p>是正前は {@code ScheduleCommentService} が
 * {@code TransactionSynchronizationManager.registerSynchronization(...)} で手書きの
 * {@code afterCommit()} を登録し、そこから本クラスを呼んでいた。<b>因果としては既にコミット後で
 * あり、通知の失敗でコメント投稿が巻き戻ることは無かった</b>——つまり #2990 本体（ROLLBACK_COUPLED）
 * には該当しない。しかし手書きの同期コールバックは字句走査では業務TX内の通知と区別できず、番人
 * {@code NotificationTransactionBoundaryGuardTest} は本経路を3件（{@code registerNotificationAfterCommit}
 * の {@code TX_NOTIFY_IN_TRY}、{@link #sendMentioned} / {@link #sendReplied} の
 * {@code DIRECT_RUNNER_CALL}）の違反として数えていた。番人が読めない正しさは退行を防げないので、
 * 同じ境界を Spring の宣言で表し直した。<b>実行タイミングは是正前と同一（コミット直後・同一スレッド・同期）</b>
 * であり、{@code @Async} は意図的に付けていない（{@link ScheduleCommentPostedEvent} の javadoc 参照）。</p>
 *
 * <h2>通知は必ず可視性でフィルタしてから送る（§6.3・最重要）</h2>
 * <p>参照経路（{@code ScheduleCommentAccessGuard} / {@code ScheduleCommentVisibilityResolver}）と
 * 通知経路が<b>同一の {@code canView} 呼び出し</b>を使うことが漏洩を構造的に塞ぐ担保である
 * （{@link ScheduleCommentViewerFilter} 経由・AC-12b/AC-18b が同じ結論になることを検証する）。</p>
 *
 * <h2>クラス全体を {@code @Transactional(REQUIRES_NEW)} にしない理由【根治済み・#2655/#2660/#2664 と同型】</h2>
 * <p>かつては通知メソッド全体が単一の {@code @Transactional(REQUIRES_NEW)} で、
 * 受信者ごとの通知作成は既定の {@code REQUIRED} 伝播で同一トランザクションに相乗りしていた。
 * この構成では 1 受信者の失敗（{@code try/catch} で捕捉）でトランザクションにロールバック
 * オンリーが立ち、コミット時に {@code UnexpectedRollbackException} となって、
 * 捕捉して継続したはずの他受信者の通知まで巻き戻っていた（本プロジェクトで #2655（居住者
 * アクティビティ）・#2660（滞納エスカレーション）・#2664（孤立メディア掃除）の3ドメインで
 * 独立に発見された既知の形）。<b>1 受信者 = 1 独立トランザクション</b>にするため、通知送信の
 * 実処理は別 Bean {@link ScheduleCommentNotificationRunner}（{@code @Transactional(REQUIRES_NEW)}）
 * へ切り出した（同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。
 * 本クラス自身はトランザクション境界を持たないオーケストレータに留める。</p>
 *
 * <h2>本クラスに {@code @Transactional} を張らない理由（現在も有効）</h2>
 * <p>{@code AFTER_COMMIT} は呼び出し元トランザクションの {@code doCommit() → triggerAfterCommit()}
 * の途中で発火し、{@code cleanupAfterCompletion()}（同期状態のクリア）は<b>まだ完了していない</b>。
 * この状態で {@code @Transactional(REQUIRED)} のメソッドを呼ぶと、既にコミット処理中で
 * まもなく破棄される外側の同期スコープへ<b>参加してしまい</b>、内部の通知作成が実際には
 * 独立してコミットされない（実測: {@code NotificationEntity} は返るが DB には残らない）。
 * 受信者ごとの実送信を {@link ScheduleCommentNotificationRunner#sendOne}（{@code REQUIRES_NEW}）へ
 * 委譲することで、受信者ごとに独立した新規トランザクションで実行する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCommentNotifier {

    private static final String MENTIONED_TYPE = "SCHEDULE_COMMENT_MENTIONED";
    private static final String REPLIED_TYPE = "SCHEDULE_COMMENT_REPLIED";
    private static final String SOURCE_TYPE = "SCHEDULE_COMMENT";
    private static final int EXCERPT_LENGTH = 100;

    private final ScheduleCommentNotificationRunner notificationRunner;
    private final ScheduleCommentViewerFilter viewerFilter;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleCommentRepository scheduleCommentRepository;

    /**
     * コメント投稿イベントを受け取り、メンション通知・返信通知を発火する（best-effort）。
     *
     * <p>業務データ（親予定・本文抜粋）はイベントに載せず、ここで ID から読み直す
     * （{@link ScheduleCommentPostedEvent} の javadoc 参照）。読み直せない場合は握りつぶさず
     * ERROR ログを残して配送を中止する（コメント自体は既にコミット済みで、巻き戻しはしない）。</p>
     *
     * @param event コメント投稿イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予定コメントはスケジュール（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすとメンションされた本人・返信された本人が気づけず会話が止まる。"
                    + "イベントは再生されないため常時実行する")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleCommentPosted(ScheduleCommentPostedEvent event) {
        if (event.scheduleId() == null || event.commentId() == null) {
            return;
        }

        ScheduleEntity schedule;
        String bodyExcerpt;
        try {
            schedule = scheduleRepository.findById(event.scheduleId()).orElse(null);
            ScheduleCommentEntity comment = scheduleCommentRepository
                    .findByIdAndScheduleIdAndDeletedAtIsNull(event.commentId(), event.scheduleId())
                    .orElse(null);
            if (schedule == null || comment == null) {
                // 投稿直後に予定ごと削除された等。通知する相手も本文も無いので配送を中止する。
                log.warn("SCHEDULE_COMMENT 通知の読み直しで対象が見つからないため配送を中止: scheduleId={}, commentId={}",
                        event.scheduleId(), event.commentId());
                return;
            }
            bodyExcerpt = excerpt(comment.getBody());
        } catch (Exception e) {
            log.error("SCHEDULE_COMMENT 通知の読み直しに失敗したため配送を中止: scheduleId={}, commentId={}",
                    event.scheduleId(), event.commentId(), e);
            return;
        }

        notify(schedule, event.commentId(), event.actorId(),
                new LinkedHashSet<>(event.mentionedUserIds() == null ? Set.<Long>of() : event.mentionedUserIds()),
                event.replyRecipientId(), bodyExcerpt);
    }

    /**
     * メンション通知・返信通知を発火する（best-effort）。
     *
     * <p>{@link #onScheduleCommentPosted}（{@code AFTER_COMMIT} 入口）から呼ばれる内部ヘルパであり、
     * 業務トランザクションの内側から直接呼んではならない。</p>
     *
     * @param schedule          親予定
     * @param commentId         投稿されたコメント ID
     * @param actorId           投稿者（通知の {@code actorId}）
     * @param mentionedUserIds  リクエストの {@code mentionedUserIds}（本人含む可・重複可）
     * @param replyRecipientId  返信通知の宛先（トップレベル投稿者。トップレベル投稿や自己返信は {@code null}）
     * @param bodyExcerpt       本文冒頭抜粋（100 文字以内・切り詰め済み）
     */
    private void notify(
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
            notificationRunner.sendOne(
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
            notificationRunner.sendOne(
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
