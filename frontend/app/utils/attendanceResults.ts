import dayjs from 'dayjs'
import type { components } from '~/types/generated'
import type { NestedScheduleResponse } from '~/utils/scheduleCalendar'

export type AttendanceResult = components['schemas']['AttendanceResponse']
/** /schedules/{id}/stats は AttendanceSummaryResponse（旧 ScheduleStatsResponse 型とは別契約）。 */
export interface AttendanceSummary {
  attending: number
  partial: number
  absent: number
  undecided: number
  total: number
}
export interface AttendanceResultRow {
  schedule: NestedScheduleResponse
  summary?: AttendanceSummary
  error?: unknown
}
export function attendanceCounts(summary: AttendanceSummary) {
  return { yes: summary.attending + summary.partial, no: summary.absent, maybe: summary.undecided }
}
export function attendanceStatusKey(response: AttendanceResult): string {
  if (response.status === 'UNDECIDED') return response.respondedAt ? 'maybe' : 'pending'
  const keys: Record<string, string> = { ATTENDING: 'yes', PARTIAL: 'partial', ABSENT: 'no' }
  return keys[response.status ?? ''] ?? 'pending'
}
export function attendanceSchedules(schedules: NestedScheduleResponse[]): NestedScheduleResponse[] {
  return schedules
    .filter((schedule) => schedule.content?.attendanceRequired === true)
    .sort((a, b) => (a.time?.startAt ?? '').localeCompare(b.time?.startAt ?? '') || a.id - b.id)
}

/** BEのLocalDateTimeは利用者TZの壁時計。オフセット付き日時だけ瞬間として変換する。 */
export function attendanceDate(value: string, timezone: string) {
  return /(?:Z|[+-]\d{2}:?\d{2})$/.test(value)
    ? dayjs(value).tz(timezone)
    : dayjs.tz(value, timezone)
}
export function formatAttendanceDate(
  value: string | null | undefined,
  timezone: string,
  locale: string,
): string {
  if (!value || !dayjs(value).isValid()) return ''
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: timezone }).format(
    attendanceDate(value, timezone).toDate(),
  )
}
