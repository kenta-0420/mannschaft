import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ScheduleListRow from '~/components/schedule/ScheduleListRow.vue'
import { useAuthStore } from '~/stores/useAuthStore'

/**
 * ScheduleListRow.vue のタイムゾーン表示ユニットテスト（Issue #2508 Phase 3）。
 *
 * 背景:
 *   修正前は `dayjs(props.event.startAt).format(...)` で日付・時刻ラベルを組み立てており、
 *   `.tz()` を通さないため BE が返すオフセット付き文字列を「ブラウザTZで描き直して」しまっていた。
 *   プロフィールTZ（America/Los_Angeles）とブラウザTZ（Asia/Tokyo）が食い違うユーザーには
 *   誤った日付・時刻が表示される。
 *
 * 検証観点:
 *   SLR-001: プロフィールTZ=LA・ブラウザTZ=JST のとき、日付・時刻ラベルが LA 壁時計になる
 *   SLR-002: 「壊れていた側」の値（ブラウザTZ=JSTで描画した場合の値）とは一致しないことも明示する
 */

const mockNavigate = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useScheduleApi', () => () => ({
  respondAttendance: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
}))

function baseEvent(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    // schedule 行の uniqueKey は `String(id)`（CalendarEventItem の javadoc参照）
    uniqueKey: '1',
    title: 'テストイベント',
    description: null,
    location: null,
    // BE は LocalDateTimeTimezoneSerializer によりユーザー(LA)TZのオフセット付きで返す。
    // 2026-08-04 09:00 PDT(-07:00) は Asia/Tokyo では 2026-08-05 01:00 になる。
    startAt: '2026-08-04T09:00:00-07:00',
    endAt: '2026-08-04T10:30:00-07:00',
    allDay: false,
    color: null,
    isPersonal: false,
    scopeName: null,
    attendanceRequired: false,
    myAttendance: null,
    ...overrides,
  }
}

/** 指定タイムゾーンで処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
function withSystemTz<T>(tz: string, fn: () => T): T {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return fn()
  } finally {
    process.env.TZ = original
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockNavigate.mockReset()
})

describe('ScheduleListRow', () => {
  it('SLR-001: プロフィールTZ=America/Los_Angeles・ブラウザTZ=Asia/Tokyoで、LA壁時計のラベルになる', async () => {
    await withSystemTz('Asia/Tokyo', async () => {
      const wrapper = await mountSuspended(ScheduleListRow, {
        props: {
          event: baseEvent(),
          scopeType: 'team',
          scopeId: 'team-1',
        },
      })

      // 注意: @pinia/nuxt はマウント時に独自の Pinia インスタンスを生成して setActivePinia() し直すため、
      // mountSuspended より前に useAuthStore().user をセットしてもマウント後に上書きされる。
      // 必ずマウント後にセットし、再描画を待つ。
      useAuthStore().user = {
        id: 1,
        email: 'user@example.com',
        fullName: 'Test User',
        profileImageUrl: null,
        timezone: 'America/Los_Angeles',
      }
      await wrapper.vm.$nextTick()

      const text = wrapper.text()
      // LA 壁時計（09:00〜10:30、8/4 (Tue)）が表示されること
      expect(text).toContain('8/4')
      expect(text).toContain('09:00')
      expect(text).toContain('10:30')

      // SLR-002: 壊れていた側（ブラウザJSTで描画した場合の値）とは一致しないこと
      // 旧実装なら 8/5 01:00〜02:30 と表示されていたはずであり、それが出ていないことを明示する
      expect(text).not.toContain('8/5')
      expect(text).not.toContain('01:00')
      expect(text).not.toContain('02:30')
    })
  })
})
