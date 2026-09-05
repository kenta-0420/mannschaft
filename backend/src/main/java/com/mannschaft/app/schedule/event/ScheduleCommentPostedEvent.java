package com.mannschaft.app.schedule.event;

import java.util.List;
import java.util.UUID;

/**
 * F03.16 予定コメント投稿の通知配送要求イベント（Issue #2990 / L8）。
 *
 * <p>{@code ScheduleCommentService#createComment} の業務トランザクションの内側で publish し、
 * {@code ScheduleCommentNotifier} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>是正前は手書きの {@code TransactionSynchronization} だった</h2>
 * <p>是正前は {@code TransactionSynchronizationManager.registerSynchronization(...)} の
 * {@code afterCommit()} から通知していた。<b>因果としては既にコミット後であり巻き戻りは起きていなかった</b>が、
 * 手書きの同期コールバックは静的解析では「業務TX内の通知」と区別がつかず、番人
 * {@code NotificationTransactionBoundaryGuardTest} は本経路を3件の違反として数え続けていた
 * （番人自身がその限界を javadoc に明記している）。<b>「番人が読めない正しさ」は退行を守れない</b>ため、
 * 同じ境界を Spring の {@code @TransactionalEventListener(AFTER_COMMIT)} で宣言し直し、
 * 構造として検証できる形に寄せた。</p>
 *
 * <h2>{@code @Async} を付けない（意図的に同期のまま）</h2>
 * <p>是正前の {@code afterCommit()} はリクエストスレッド上で同期実行されていた。本イベントの
 * リスナーも {@code @Async} を付けず同じ同期実行を保つ。呼び出し元は既にコミット済みであり
 * 巻き戻りは起こり得ないため、別スレッドへ逃がす動機は無い。逆に非同期化すると
 * {@code ScheduleCommentNotificationPartialFailureIT}（1受信者の失敗が他受信者へ波及しないことを
 * 実 DB で検証する回帰テスト）が競合するだけで、得るものが無い。</p>
 *
 * <h2>イベントには ID だけを載せる</h2>
 * <p>本文抜粋・予定の内容は業務データであるため積まず、配送側が {@code commentId} /
 * {@code scheduleId} から読み直す。読み直しに失敗した場合は握りつぶさず ERROR ログを残して
 * 配送を中止する。</p>
 *
 * @param scheduleId       親予定 ID（可視性フィルタ・通知スコープの読み直しキー）
 * @param commentId        投稿されたコメント ID（本文抜粋の読み直しキー）
 * @param actorId          投稿者ユーザー ID
 * @param mentionedUserIds リクエストで指定されたメンション先（本人含む可・重複可）
 * @param replyRecipientId 返信通知の宛先（トップレベル投稿・自己返信は {@code null}）
 */
public record ScheduleCommentPostedEvent(
        Long scheduleId,
        UUID commentId,
        Long actorId,
        List<Long> mentionedUserIds,
        Long replyRecipientId) {
}
