import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * snoozePreset ユーティリティのユニットテスト。
 *
 * - SNOOZE-001: in3h — now + 3時間 の ISO Z 文字列
 * - SNOOZE-002: tonight — 当日 21:00（21時以降なら翌日 21:00）
 * - SNOOZE-003: tomorrowMorning — 翌日 09:00
 * - SNOOZE-004: nextWeek — 翌月曜 09:00
 * - SNOOZE-005: 戻り値は Z で終わる（オフセット付き）
 * - SNOOZE-006: 戻り値は現在時刻より未来
 */

const TZ = 'Asia/Tokyo'

// dayjs をモックして固定日時を返す
// 各テストケースで異なる "現在時刻" を使いたいため、vi.setSystemTime を利用する
describe('computeSnoozeUntil', () => {
  let computeSnoozeUntil: (preset: import('~/utils/snoozePreset').SnoozePreset, tz?: string) => string

  beforeEach(async () => {
    // モジュールを毎回再インポート（動的インポートで dayjs のモックを有効化）
    const mod = await import('~/utils/snoozePreset')
    computeSnoozeUntil = mod.computeSnoozeUntil
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // ──────────────────────────────────────
  // SNOOZE-001: in3h
  // ──────────────────────────────────────
  describe('SNOOZE-001: in3h — now + 3時間', () => {
    it('3時間後のISO文字列を返す', () => {
      // 2026-06-03T10:00:00+09:00 (JST) = 2026-06-03T01:00:00Z
      vi.setSystemTime(new Date('2026-06-03T01:00:00Z'))

      const result = computeSnoozeUntil('in3h', TZ)
      // 期待: 2026-06-03T04:00:00Z
      expect(result).toBe('2026-06-03T04:00:00.000Z')
    })
  })

  // ──────────────────────────────────────
  // SNOOZE-002: tonight — 当日 21:00 JST
  // ──────────────────────────────────────
  describe('SNOOZE-002: tonight', () => {
    it('21時前なら当日 21:00 JST', () => {
      // 2026-06-03T10:00:00+09:00 (10時 JST) = 2026-06-03T01:00:00Z
      vi.setSystemTime(new Date('2026-06-03T01:00:00Z'))

      const result = computeSnoozeUntil('tonight', TZ)
      // 21:00 JST = 12:00 UTC
      expect(result).toBe('2026-06-03T12:00:00.000Z')
    })

    it('21時ちょうどなら翌日 21:00 JST', () => {
      // 2026-06-03T21:00:00+09:00 = 2026-06-03T12:00:00Z
      vi.setSystemTime(new Date('2026-06-03T12:00:00Z'))

      const result = computeSnoozeUntil('tonight', TZ)
      // 翌日 21:00 JST = 2026-06-04T12:00:00Z
      expect(result).toBe('2026-06-04T12:00:00.000Z')
    })

    it('21時以降なら翌日 21:00 JST', () => {
      // 2026-06-03T22:30:00+09:00 = 2026-06-03T13:30:00Z
      vi.setSystemTime(new Date('2026-06-03T13:30:00Z'))

      const result = computeSnoozeUntil('tonight', TZ)
      // 翌日 21:00 JST = 2026-06-04T12:00:00Z
      expect(result).toBe('2026-06-04T12:00:00.000Z')
    })
  })

  // ──────────────────────────────────────
  // SNOOZE-003: tomorrowMorning — 翌日 09:00 JST
  // ──────────────────────────────────────
  describe('SNOOZE-003: tomorrowMorning', () => {
    it('翌日 09:00 JST の ISO 文字列を返す', () => {
      // 2026-06-03T10:00:00+09:00 = 2026-06-03T01:00:00Z
      vi.setSystemTime(new Date('2026-06-03T01:00:00Z'))

      const result = computeSnoozeUntil('tomorrowMorning', TZ)
      // 翌日 09:00 JST = 2026-06-04T00:00:00Z
      expect(result).toBe('2026-06-04T00:00:00.000Z')
    })
  })

  // ──────────────────────────────────────
  // SNOOZE-004: nextWeek — 翌月曜 09:00 JST
  // ──────────────────────────────────────
  describe('SNOOZE-004: nextWeek', () => {
    it('火曜日なら翌月曜 09:00 JST', () => {
      // 2026-06-02 はどの曜日か確認: 火曜日（day=2）
      // 2026-06-02T10:00:00+09:00 = 2026-06-02T01:00:00Z
      vi.setSystemTime(new Date('2026-06-02T01:00:00Z'))

      const result = computeSnoozeUntil('nextWeek', TZ)
      // 次の月曜 = 2026-06-08, 09:00 JST = 00:00 UTC
      expect(result).toBe('2026-06-08T00:00:00.000Z')
    })

    it('月曜日なら翌々週の月曜ではなく次の月曜（7日後）', () => {
      // 2026-06-01 は月曜日（day=1）
      // 2026-06-01T10:00:00+09:00 = 2026-06-01T01:00:00Z
      vi.setSystemTime(new Date('2026-06-01T01:00:00Z'))

      const result = computeSnoozeUntil('nextWeek', TZ)
      // 次の月曜 = 7日後 = 2026-06-08, 09:00 JST = 00:00 UTC
      expect(result).toBe('2026-06-08T00:00:00.000Z')
    })
  })

  // ──────────────────────────────────────
  // SNOOZE-005: 戻り値は Z で終わる
  // ──────────────────────────────────────
  describe('SNOOZE-005: 戻り値のフォーマット', () => {
    it.each(['in3h', 'tonight', 'tomorrowMorning', 'nextWeek'] as const)(
      'preset=%s は Z 付き ISO-8601 文字列を返す',
      (preset) => {
        vi.setSystemTime(new Date('2026-06-03T01:00:00Z'))
        const result = computeSnoozeUntil(preset, TZ)
        expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
      },
    )
  })

  // ──────────────────────────────────────
  // SNOOZE-006: 戻り値は現在時刻より未来
  // ──────────────────────────────────────
  describe('SNOOZE-006: 戻り値は常に未来', () => {
    it.each(['in3h', 'tonight', 'tomorrowMorning', 'nextWeek'] as const)(
      'preset=%s の結果は現在より未来',
      (preset) => {
        vi.setSystemTime(new Date('2026-06-03T01:00:00Z'))
        const now = Date.now()
        const result = computeSnoozeUntil(preset, TZ)
        expect(new Date(result).getTime()).toBeGreaterThan(now)
      },
    )
  })
})
