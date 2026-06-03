import dayjs from 'dayjs'

/**
 * スヌーズプリセットの型定義。
 * useInboxStore.InboxSnoozePreset と同じ 4 種。
 * NotificationList.vue や他コンポーネントから共有利用できるよう utils に分離。
 */
export type SnoozePreset = 'in3h' | 'tonight' | 'tomorrowMorning' | 'nextWeek'

/**
 * スヌーズプリセットから ISO-8601 文字列（UTC, Z 付き）を計算する。
 *
 * useInboxStore.computeSnoozeUntil と同一ロジック。
 * dayjs.tz プラグインが plugins/dayjs.ts でグローバルに登録済みであること前提。
 *
 * @param preset - スヌーズプリセット
 * @param timezone - IANA タイムゾーン識別子（省略時: 'Asia/Tokyo'）
 * @returns ISO-8601 UTC 文字列（末尾 "Z"）
 */
export function computeSnoozeUntil(preset: SnoozePreset, timezone = 'Asia/Tokyo'): string {
  const now = dayjs().tz(timezone)

  switch (preset) {
    case 'in3h':
      return now.add(3, 'hour').toISOString()
    case 'tonight': {
      const tonight = now.hour(21).minute(0).second(0).millisecond(0)
      // 21 時を過ぎている場合は翌日 21 時
      return (now.hour() >= 21 ? tonight.add(1, 'day') : tonight).toISOString()
    }
    case 'tomorrowMorning':
      return now.add(1, 'day').hour(9).minute(0).second(0).millisecond(0).toISOString()
    case 'nextWeek': {
      // 翌月曜 09:00
      const daysUntilMonday = (8 - now.day()) % 7 || 7
      return now
        .add(daysUntilMonday, 'day')
        .hour(9)
        .minute(0)
        .second(0)
        .millisecond(0)
        .toISOString()
    }
  }
}
