import { ref } from 'vue'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ScheduleMobileListView from '~/components/schedule/ScheduleMobileListView.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import { useAuthStore } from '~/stores/useAuthStore'

interface MobileListViewTestProps {
  year: number
  month: number
  events: CalendarEventItem[]
  scopeType: 'team' | 'organization'
  scopeId: string
  emptyMessage: string
  dimmed?: boolean
}

/**
 * F03.19 §6.8（Wave 3-c）: モバイル共通リストビュー ScheduleMobileListView のユニットテスト。
 *
 * AC-14 / AC-14b の裏付け:
 *   - 各行に時刻・タイトル・レイヤー色の縦バーが見える
 *   - 空状態は呼び出し側が渡した文言（ページごとに異なるキー）がそのまま出る
 *   - 月ナビの前後移動は prevMonth/nextMonth を emit するだけ（呼び出し側の月移動ロジックは不変）
 *   - 行タップは「id だけ」ではなく元の CalendarEventItem をそのまま emit する
 *     （reflection 行など id が -1 で衝突しうる行を id 非依存で判別できるようにするため）
 */

// [2] Codex 検分指摘: periodLabel は Intl.DateTimeFormat(locale.value, ...) で生成するため、
// useI18n モックは t だけでなく locale（切り替え可能な ref）も返す必要がある。
const mockLocale = ref('ja')
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: mockLocale }))
mockNuxtImport('useScheduleApi', () => () => ({ respondAttendance: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))

function makeEvent(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    uniqueKey: '1',
    title: 'テストイベント',
    description: null,
    location: null,
    startAt: '2026-08-04T09:00:00+09:00',
    endAt: '2026-08-04T10:30:00+09:00',
    allDay: false,
    color: '#2563eb',
    isPersonal: false,
    scopeName: null,
    attendanceRequired: false,
    myAttendance: null,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockLocale.value = 'ja'
})

async function mountView(props: MobileListViewTestProps) {
  const wrapper = await mountSuspended(ScheduleMobileListView, { props })
  useAuthStore().user = {
    id: 1,
    email: 'user@example.com',
    fullName: 'Test User',
    profileImageUrl: null,
    timezone: 'Asia/Tokyo',
  }
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('ScheduleMobileListView', () => {
  it('SMLV-001: イベントが1件も無いとき、呼び出し側が渡した空状態メッセージが表示される', async () => {
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: 'この期間に予定はありません',
    })

    expect(wrapper.text()).toContain('この期間に予定はありません')
    expect(wrapper.find('[data-testid="schedule-list-row-wrap"]').exists()).toBe(false)
  })

  it('SMLV-002: イベントがあるとき、各行にレイヤー色の縦バー（BE解決済み content.color）が付く', async () => {
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [makeEvent({ color: '#dc2626' })],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: '空です',
    })

    const bar = wrapper.get('[data-testid="schedule-list-row-color-bar"]')
    expect(bar.attributes('style')).toContain('background-color: #dc2626')

    // 行本体（ScheduleListRow）はそのまま描画され、タイトルが見える
    expect(wrapper.text()).toContain('テストイベント')
  })

  it('SMLV-003: 色が null のイベントは transparent（FE 側で色を算出しない）', async () => {
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [makeEvent({ color: null })],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: '空です',
    })

    const bar = wrapper.get('[data-testid="schedule-list-row-color-bar"]')
    expect(bar.attributes('style')).toContain('background-color: transparent')
  })

  it('SMLV-004: 月ナビの左右ボタンは prevMonth/nextMonth を emit するだけ', async () => {
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: '空です',
    })

    const prevButton = wrapper.find('[aria-label="schedule.list.prevMonth"]')
    const nextButton = wrapper.find('[aria-label="schedule.list.nextMonth"]')
    expect(prevButton.exists()).toBe(true)
    expect(nextButton.exists()).toBe(true)

    await prevButton.trigger('click')
    await nextButton.trigger('click')

    expect(wrapper.emitted('prevMonth')).toHaveLength(1)
    expect(wrapper.emitted('nextMonth')).toHaveLength(1)
  })

  it('SMLV-005: 行タップは id だけでなく元の CalendarEventItem をそのまま emit する（reflection 行の id 非依存判別のため）', async () => {
    const reflectionEvent = makeEvent({
      id: -1,
      uniqueKey: 'ref:abc',
      isReflection: true,
      referenceUuid: 'abc',
      referenceKind: 'REFLECTION_ENTRY',
      title: '振り返り',
    })
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [reflectionEvent],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: '空です',
    })

    await wrapper.get('[data-testid="schedule-list-row"] button').trigger('click')

    const emitted = wrapper.emitted('open')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toMatchObject({
      uniqueKey: 'ref:abc',
      isReflection: true,
      referenceUuid: 'abc',
      referenceKind: 'REFLECTION_ENTRY',
    })
  })

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

  it('SMLV-006: 月ナビの見出しは選択中のロケールから生成され、直書きの日本語では固定されない', async () => {
    mockLocale.value = 'en'
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: 'empty',
    })

    // en ロケールでは Intl.DateTimeFormat('en', { year: 'numeric', month: 'long' }) の綴りになる。
    // 旧実装（`${year}年${month}月` の直書き）なら、ロケールを en にしても常に日本語のまま出ていた。
    expect(wrapper.text()).toContain('August 2026')
    expect(wrapper.text()).not.toContain('2026年8月')
  })

  it('SMLV-007: ロケールを ja に切り替えると見出しが日本語の年月表記に追随する', async () => {
    mockLocale.value = 'ja'
    const wrapper = await mountView({
      year: 2026,
      month: 8,
      events: [],
      scopeType: 'team',
      scopeId: 't1',
      emptyMessage: 'empty',
    })

    expect(wrapper.text()).toContain('2026年8月')
  })

  it('SMLV-008: 端末TZが UTC+14（Pacific/Kiritimati）でも見出しの年月が1日もずれない', async () => {
    // W3-a（CalendarWeekGrid.vue）が一度踏んだ罠と同根: Intl に timeZone を渡し忘れると、
    // UTC 月初として組み立てた Date が端末ローカルで再解釈され、UTC+α の端末では
    // 前月末や翌月扱いにずれる。year=2026/month=1（1月1日境界）で確認する。
    await withSystemTz('Pacific/Kiritimati', async () => {
      const wrapper = await mountView({
        year: 2026,
        month: 1,
        events: [],
        scopeType: 'team',
        scopeId: 't1',
        emptyMessage: 'empty',
      })

      expect(wrapper.text()).toContain('2026年1月')
      expect(wrapper.text()).not.toContain('2025年12月')
    })
  })
})
