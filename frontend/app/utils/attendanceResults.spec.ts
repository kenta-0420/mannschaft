// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import {
  attendanceCounts,
  attendanceDate,
  attendanceSchedules,
  attendanceStatusKey,
  formatAttendanceDate,
} from './attendanceResults'
dayjs.extend(utc)
dayjs.extend(timezone)
beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-24T03:00:00Z'))
})
afterAll(() => vi.useRealTimers())

describe('出席確認の実API契約', () => {
  it('一部出席を出席人数へ加え、未定人数を保つ（AC1/2/5）', () => {
    expect(
      attendanceCounts({ attending: 2, partial: 3, absent: 4, undecided: 5, total: 14 }),
    ).toEqual({ yes: 5, no: 4, maybe: 5 })
    expect(
      attendanceCounts({ attending: 0, partial: 0, absent: 0, undecided: 0, total: 0 }),
    ).toEqual({ yes: 0, no: 0, maybe: 0 })
  })
  it('回答済み未定と未回答、一部出席を区別する（AC2）', () => {
    expect(attendanceStatusKey({ status: 'PARTIAL' })).toBe('partial')
    expect(attendanceStatusKey({ status: 'UNDECIDED', respondedAt: '2026-10-24T12:00:00' })).toBe(
      'maybe',
    )
    expect(attendanceStatusKey({ status: 'UNDECIDED' })).toBe('pending')
  })
  it('通常予定が先頭に多くても出席確認対象のみを残す（AC7）', () => {
    const schedules = Array.from({ length: 20 }, (_, id) => ({
      id,
      content: { attendanceRequired: id >= 10 },
      time: { startAt: '2026-10-24T12:00:00' },
    }))
    expect(attendanceSchedules(schedules).map((item) => item.id)).toEqual([
      10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
    ])
    expect(attendanceSchedules([{ id: 1, content: null }, { id: 2 }])).toEqual([])
  })
  it.each(['Asia/Tokyo', 'America/Los_Angeles'])(
    '終日・深夜のLocalDateTimeを%sの暦日に保つ（AC6）',
    (zone) => {
      expect(attendanceDate('2026-10-24T00:00:00', zone).format('YYYY-MM-DD HH:mm')).toBe(
        '2026-10-24 00:00',
      )
      expect(attendanceDate('2026-10-24T23:59:59', zone).format('YYYY-MM-DD HH:mm')).toBe(
        '2026-10-24 23:59',
      )
      expect(formatAttendanceDate(null, zone, 'en')).toBe('')
      expect(formatAttendanceDate('invalid', zone, 'en')).toBe('')
    },
  )
})
