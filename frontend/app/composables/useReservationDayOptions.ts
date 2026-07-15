import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'

/**
 * 曜日選択肢・時刻ヘルパーの共通化（F03.4.5 §3.1/§3.2）。
 *
 * ReservationBusinessHoursManager.vue（新設）と WeeklyScheduleManager.vue（旧 SlotTemplateManager）
 * の双方が同じ曜日トグル・30分刻み Select を使うため、ここに集約する。
 *
 * ラベルは既存 `schedule.recurrence.days.*` を再利用する（新設しない・SlotTemplateManager からの流用）。
 * value は BE 正準の3文字大文字コード（'MON'..'SUN'）。写経元 ScheduleEventRecurrenceInput.vue の
 * 曜日トグルは 'MONDAY' 等のフルネームを emit するが、BE の `ReservationDayOfWeek` enum は
 * 3文字大文字のみ受理する（フルネーム送信は Jackson デシリアライズ失敗で 400）。
 */
export const RESERVATION_DAY_OPTIONS: ReadonlyArray<{ value: ReservationDayOfWeekCode; labelKey: string }> = [
  { value: 'SUN', labelKey: 'schedule.recurrence.days.SUNDAY' },
  { value: 'MON', labelKey: 'schedule.recurrence.days.MONDAY' },
  { value: 'TUE', labelKey: 'schedule.recurrence.days.TUESDAY' },
  { value: 'WED', labelKey: 'schedule.recurrence.days.WEDNESDAY' },
  { value: 'THU', labelKey: 'schedule.recurrence.days.THURSDAY' },
  { value: 'FRI', labelKey: 'schedule.recurrence.days.FRIDAY' },
  { value: 'SAT', labelKey: 'schedule.recurrence.days.SATURDAY' },
]

/** 30分刻みの時刻選択肢（00:00〜23:30・'HH:mm'）。 */
export function buildHalfHourTimeOptions(): Array<{ label: string; value: string }> {
  const opts: Array<{ label: string; value: string }> = []
  for (let h = 0; h < 24; h++) {
    for (const m of [0, 30]) {
      const v = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      opts.push({ label: v, value: v })
    }
  }
  return opts
}

/** BE の 'HH:mm:ss' 表現を FE の 'HH:mm' へ丸める。値なしは空文字。 */
export function toHm(value?: string | null): string {
  return value ? value.slice(0, 5) : ''
}

/** 'HH:mm' を分単位の整数へ変換する（縮小判定など時刻比較用）。不正値は 0。 */
export function hmToMinutes(value: string): number {
  const [h, m] = value.split(':').map(Number)
  return (h || 0) * 60 + (m || 0)
}
