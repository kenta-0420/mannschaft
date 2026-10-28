package com.mannschaft.app.notification.confirmable.event;

import java.util.List;

/**
 * 確認通知の手動リマインド再送の配送要求イベント（Issue #2997 / CMP-260827-1152 G4-b）。
 *
 * <p>{@code ConfirmableNotificationConfirmService#resendReminder} の業務トランザクション内で publish され、
 * {@link ConfirmableReminderResendNotificationListener} が {@code AFTER_COMMIT} で受け取る。
 * 業務TXがロールバックすれば通知は 1 件も作られない。</p>
 *
 * <p>「イベントには ID だけを載せる」原則に従い、件名・本文・優先度・スコープ・遷移先は
 * {@code confirmable_notifications} を {@code notificationId} で読み直して得る。
 * 読み直せないもの（publish 時点の未確認かつ非除外の受信者集合）だけを載せる。
 * 受信者は呼び出し側で確定したスナップショットで、不変コピーを渡すこと。</p>
 *
 * @param notificationId   確認通知ID
 * @param recipientUserIds 再送先ユーザーID（未確認かつ非除外・1 人以上）
 */
public record ConfirmableReminderResendEvent(Long notificationId, List<Long> recipientUserIds) {

    public ConfirmableReminderResendEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }
}
