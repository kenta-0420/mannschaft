import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useDatetime } from '~/composables/useDatetime'
import { useAuthStore } from '~/stores/useAuthStore'

/**
 * useDatetime のロケール対応（CMP-261104-0335）。
 * AC 0335-1（言語ごとの書式）/ 0335-5（言語切替後の再描画・プロフィールTZ維持）/ ja は従来出力と同一。
 */
type LocaleHolder = { locale: { value: string } }
const i18n = () => useNuxtApp().$i18n as unknown as LocaleHolder

function setUserTimezone(timezone: string) {
  useAuthStore().user = { id: 1, email: 'u@example.com', fullName: 'U', profileImageUrl: null, timezone }
}

const ISO = '2026-05-22T09:15:20+09:00'
let original = 'ja'

beforeEach(() => {
  setActivePinia(createPinia())
  original = i18n().locale.value
})
afterEach(() => {
  i18n().locale.value = original
})

describe('useDatetime ロケール対応', () => {
  it('ja は従来と完全に同一（YYYY/MM/DD, YYYY/MM/DD HH:mm, HH:mm）', () => {
    i18n().locale.value = 'ja'
    setUserTimezone('Asia/Tokyo')
    const { formatDate, formatDateTime, formatTime } = useDatetime()
    expect(formatDate(ISO)).toBe('2026/05/22')
    expect(formatDateTime(ISO)).toBe('2026/05/22 09:15')
    expect(formatTime(ISO)).toBe('09:15')
  })

  it('言語切替後は同じ関数がその言語の書式で再描画される', () => {
    setUserTimezone('Asia/Tokyo')
    const { formatDate } = useDatetime()
    i18n().locale.value = 'ja'
    expect(formatDate(ISO)).toBe('2026/05/22')
    i18n().locale.value = 'en'
    expect(formatDate(ISO)).toBe('05/22/2026')
    i18n().locale.value = 'de'
    expect(formatDate(ISO)).toBe('22.05.2026')
  })

  it('プロフィールTZを維持する（ロケールを変えてもTZ換算は同じ）', () => {
    i18n().locale.value = 'en'
    setUserTimezone('America/Los_Angeles')
    const { formatDateTime } = useDatetime()
    expect(formatDateTime(ISO)).toBe('05/21/2026 17:15')
  })

  it('暦日・暦日範囲・時刻範囲は共通関数経由で言語に従う（西側TZでも前日にならない）', () => {
    i18n().locale.value = 'en'
    setUserTimezone('America/Los_Angeles')
    const { formatCalendarDay, formatCalendarDayRange, formatTimeRange } = useDatetime()
    expect(formatCalendarDay('2026-11-04')).toContain('Wed')
    expect(formatCalendarDayRange('2026-11-04', '2026-11-04')).toBe(formatCalendarDay('2026-11-04'))
    expect(formatTimeRange('09:00:00', '11:00:00')).not.toMatch(/\d{2}:\d{2}:\d{2}/)
    i18n().locale.value = 'ja'
    expect(formatCalendarDay('2026-11-04')).toBe('11月4日（水）')
    expect(formatCalendarDayRange('', '')).toBe('')
    expect(formatTimeRange('09:00:00', '11:00:00')).toBe('09:00〜11:00')
  })

  it.each(['en', 'zh', 'ko', 'es', 'de'])('%s: 暦日範囲に日本語の「〜」「曜」が残らない', (loc) => {
    i18n().locale.value = loc
    const { formatCalendarDayRange, formatTimeRange } = useDatetime()
    const out = `${formatCalendarDayRange('2026-12-31', '2027-01-02')} ${formatTimeRange('09:00', '11:00')}`
    expect(out).not.toContain('〜')
  })
})

describe('useDatetime 不正入力は自動補正されず空文字（Codex 再確認 P2）', () => {
  it('実在しない暦日・範囲外の時刻は formatDate/formatDateTime/formatTime が空文字', () => {
    i18n().locale.value = 'ja'
    setUserTimezone('Asia/Tokyo')
    const { formatDate, formatDateTime, formatTime } = useDatetime()
    for (const bad of ['2026-02-30', '2026-02-30T25:00:00', '2026-11-04junk', '2025-02-29T10:00:00+09:00', '2026-13-01', '2026-11-04T24:00:00']) {
      expect(formatDate(bad), bad).toBe('')
      expect(formatDateTime(bad), bad).toBe('')
      expect(formatTime(bad), bad).toBe('')
    }
  })
  it('正常な Instant 形式（Z / オフセット / ミリ秒 / 閏日）は従来どおり', () => {
    i18n().locale.value = 'ja'
    setUserTimezone('Asia/Tokyo')
    const { formatDateTime } = useDatetime()
    expect(formatDateTime('2026-05-22T09:15:20+09:00')).toBe('2026/05/22 09:15')
    expect(formatDateTime('2026-05-22T00:15:20Z')).toBe('2026/05/22 09:15')
    expect(formatDateTime('2026-05-22T00:15:20.123Z')).toBe('2026/05/22 09:15')
    expect(formatDateTime('2024-02-29T00:00:00Z')).toBe('2024/02/29 09:00')
  })
})
