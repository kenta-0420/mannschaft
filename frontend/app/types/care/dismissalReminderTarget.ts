/**
 * F03.12 §16 / Phase11 主催未解散イベントのレスポンス型。
 *
 * BE DTO 出典: {@code DismissalReminderTargetResponse}
 *
 * <p>{@code GET /api/v1/events/my-organizing/dismissal-reminders} の各要素。
 * 主催者向けダッシュボード Widget {@code WidgetEventDismissalReminder} がカード描画用に使う。</p>
 */
export interface DismissalReminderTarget {
  /** イベントID。 */
  eventId: number
  /** イベント表示名（subtitle 優先、なければ slug）。 */
  eventName: string
  /** チームID（scopeType=TEAM 時の scopeId）。 */
  teamId: number
  /** チーム名。 */
  teamName: string
  /** スケジュールの終了予定時刻（ISO-8601）。 */
  endAt: string
  /** 終了予定時刻からの経過分数（0 以上）。 */
  minutesPassed: number
  /** 主催者へのリマインド送信回数（0〜3）。 */
  reminderCount: number
}
