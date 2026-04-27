/**
 * F03.12 §16 イベント解散通知の型定義。
 *
 * BE DTO 出典:
 *   - {@code DismissalRequest}
 *   - {@code DismissalStatusResponse}
 */

/**
 * 解散通知送信リクエスト。
 *
 * すべてのフィールドが任意。{@link message} 省略時は BE が "解散しました" を使用する。
 * {@link notifyGuardians} 省略時は true として扱われる。
 */
export interface DismissalRequest {
  /** 解散メッセージ（500 文字以内、任意）。 */
  message?: string
  /** 実際の終了日時（ISO-8601、任意）。省略時は BE 側で現在日時を使用。 */
  actualEndAt?: string
  /** 見守り者にも通知するか（任意、省略時 true）。 */
  notifyGuardians?: boolean
}

/**
 * 解散通知の送信状態レスポンス。
 *
 * GET /dismissal/status の返却値。
 * {@link dismissed} が true なら既に送信済み（POST すると 409 Conflict）。
 */
export interface DismissalStatusResponse {
  /** 解散通知送信日時（未送信時 null、ISO-8601）。 */
  dismissalNotificationSentAt: string | null
  /** 解散通知を送信したユーザーID（未送信時 null）。 */
  dismissalNotifiedByUserId: number | null
  /** 主催者へのリマインド送信回数（0〜3）。 */
  reminderCount: number
  /** 最終リマインド送信日時（ISO-8601、未送信時 null）。 */
  lastReminderAt: string | null
  /** 解散通知送信済みかどうか。 */
  dismissed: boolean
}
