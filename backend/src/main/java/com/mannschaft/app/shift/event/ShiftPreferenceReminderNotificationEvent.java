package com.mannschaft.app.shift.event;

import java.util.List;

/**
 * シフト希望提出リマインド通知の配送要求イベント（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>{@code ShiftPreferenceReminderRunner#remindOne}（定期バッチの項目TX）と
 * {@code ShiftPreferenceReminderBatchService#triggerManualReminder}（手動リマインドのTX）が、
 * 業務状態の確定と同じトランザクションで publish し、{@link ShiftPreferenceReminderNotificationListener} が
 * {@code AFTER_COMMIT} で受け取る。</p>
 *
 * @param kind             リマインド種別（通知種別・文面を決める）
 * @param scheduleId       シフト表ID（通知の source）
 * @param teamId           チームID（通知のスコープ）
 * @param scheduleTitle    シフト表タイトル（本文に埋め込む。publish 時点の値）
 * @param recipientUserIds 受信者（未提出メンバー。解決は業務側で 1 回だけ行う）
 */
public record ShiftPreferenceReminderNotificationEvent(
        ShiftPreferenceReminderKind kind,
        Long scheduleId,
        Long teamId,
        String scheduleTitle,
        List<Long> recipientUserIds) {
}
