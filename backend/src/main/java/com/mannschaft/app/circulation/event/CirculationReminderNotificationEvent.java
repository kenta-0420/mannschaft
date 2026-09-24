package com.mannschaft.app.circulation.event;

import java.util.List;

/**
 * 回覧手動リマインドの通知発火イベント（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code CirculationService#remindDocument} は業務トランザクションの内側で本イベントを publish
 * するだけに留める。<b>業務上の事実（ID）だけ</b>を積み、文書タイトルの解決・ロケール解決・
 * 件名/本文の組み立ては {@link CirculationReminderNotificationListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <h2>タイトルをイベントに載せない理由</h2>
 * <p>確定設計（Issue #2834 コメント）の「IDと種別だけ。描画済み文字列を載せるな」に従う。
 * 文書タイトルは配送時点で回覧文書行から読み直す（回覧文書は督促によって削除・状態遷移しないため、
 * {@code AFTER_COMMIT} の時点でも必ず生存している。§「削除済み source を参照しない」の確認済み事項）。</p>
 *
 * <h2>受信者ごとに 1 イベントを投げない理由</h2>
 * <p>受信者数ぶんの {@code @Async} タスクを {@code event-pool} へ投入するとキューを食い潰すため、
 * 1 回の督促から通知要求一覧を 1 イベントとして発行する（ロットA の
 * {@code OnboardingReminderNotificationEvent} と同型）。</p>
 *
 * @param documentId     回覧文書ID（{@code sourceId} 兼タイトル解決キー）
 * @param actorId        督促操作者ユーザーID
 * @param recipientUserIds 督促対象（{@code PENDING} 受信者）のユーザーID一覧
 */
public record CirculationReminderNotificationEvent(
        Long documentId,
        Long actorId,
        List<Long> recipientUserIds) {
}
