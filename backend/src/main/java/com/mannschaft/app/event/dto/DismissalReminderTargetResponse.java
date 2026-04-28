package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 解散通知未送信リマインダー対象イベントのレスポンス DTO。F03.12 §16 / Phase11。
 *
 * <p>{@code GET /api/v1/events/my-organizing/dismissal-reminders} の返却要素。
 * 「ログインユーザーが主催している、終了予定時刻を過ぎたが未解散のイベント」1 件分を表す。</p>
 *
 * <p>主催者向けダッシュボードの {@code WidgetEventDismissalReminder} で「解散通知を送る」
 * カードを描画するために使用する。</p>
 */
@Getter
@RequiredArgsConstructor
@Builder
public class DismissalReminderTargetResponse {

    /** イベントID。 */
    private final Long eventId;

    /** イベント表示名（subtitle 優先、なければ slug）。 */
    private final String eventName;

    /** チームID（scopeType=TEAM 時の scopeId）。 */
    private final Long teamId;

    /** チーム名。 */
    private final String teamName;

    /** スケジュールの終了予定時刻（ISO-8601）。 */
    private final LocalDateTime endAt;

    /** 終了予定時刻からの経過分数（{@code now - endAt} の分換算、0 以上）。 */
    private final long minutesPassed;

    /** 主催者へのリマインド送信回数（0〜3）。 */
    private final int reminderCount;
}
