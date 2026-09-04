package com.mannschaft.app.event.event;

import java.util.List;

/**
 * F03.12 ケア対象者見守り通知の発火イベント（Issue #2990 L5 TX_NOTIFY_BARE 是正）。
 *
 * <p>{@code EventCheckinService#staffCheckin} / {@code #selfCheckin}、
 * {@code EventRsvpService#submitRsvp}、{@code EventRollCallService#submitRollCall} は
 * 業務トランザクションの内側で本イベントを publish するだけに留め、
 * {@code CareEventNotificationService} の実呼び出しは
 * {@code EventCareNotificationTriggerListener}（{@code AFTER_COMMIT}）で行う。</p>
 *
 * <h2>{@code CareEventNotificationService} 自体は変更しない</h2>
 * <p>{@code EventDismissalNotificationListener}（CMP-056 第1群ロットB）と同じ方針である。
 * 変えるのは<b>呼び出し位置</b>だけで、業務トランザクションの内側から本リスナー（業務TX外）へ移す。
 * 冪等チェック（{@code event_care_notification_logs} の存在確認）も通知本文の組み立ても
 * {@code CareEventNotificationService} が持ったままであり、AFTER_COMMIT へ移しても
 * 二重送信は起きない。</p>
 *
 * <h2>載せるのは ID と種別だけ</h2>
 * <p>ケア対象者名・イベントラベル・ケアカテゴリはいずれも
 * {@code CareEventNotificationService} が {@code eventId} / {@code userId} から読み直す業務データで
 * あるため積まない。日時型を載せると {@code DateTimeAndZoneGuardTest} が弾く。</p>
 *
 * @param eventId              対象イベントID
 * @param kind                 通知トリガーの種別
 * @param careRecipientUserIds 通知対象（ケア対象者）のユーザーID一覧。
 *                             呼び出し元が業務TX内で確定させた対象だけを載せる
 */
public record EventCareNotificationTriggerEvent(
        Long eventId,
        Kind kind,
        List<Long> careRecipientUserIds) {

    /** F03.12 の見守り通知トリガー。 */
    public enum Kind {
        /** RSVP で ATTENDING を回答した（{@code notifyRsvpConfirmed}）。 */
        RSVP_CONFIRMED,
        /** チェックイン（スタッフスキャン / セルフ / 点呼の PRESENT。{@code notifyCheckin}）。 */
        CHECKIN
    }
}
