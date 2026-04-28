import type { DismissalReminderTarget } from '~/types/care'

interface ApiResponse<T> {
  data: T
}

/**
 * F03.12 §16 / Phase11 主催未解散イベント取得 composable。
 *
 * <p>BE エンドポイント:</p>
 * <ul>
 *   <li>GET /api/v1/events/my-organizing/dismissal-reminders</li>
 * </ul>
 *
 * <p>主催者向けダッシュボード Widget {@code WidgetEventDismissalReminder} から呼ばれ、
 * 「終了予定時刻を過ぎたが解散通知が未送信」のチームイベントをリスト形式で返す。</p>
 */
export function useDismissalReminders() {
  const api = useApi()

  /**
   * 主催未解散イベント一覧を取得する。
   *
   * @returns 主催未解散イベントのリスト（endAt 昇順）
   */
  async function fetchTargets(): Promise<DismissalReminderTarget[]> {
    const res = await api<ApiResponse<DismissalReminderTarget[]>>(
      '/api/v1/events/my-organizing/dismissal-reminders',
    )
    return res.data
  }

  return {
    fetchTargets,
  }
}
