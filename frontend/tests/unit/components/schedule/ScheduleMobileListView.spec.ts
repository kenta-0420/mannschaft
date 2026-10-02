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

  /**
   * 指定タイムゾーンで非同期処理を実行する（Node は process.env.TZ の実行時変更を反映する）。
   *
   * [Codex 検分指摘・二巡目] `fn` は `async` コールバックであり `Promise` を返す。旧実装の
   * `return fn()` は同期関数のシグネチャ（`() => T`）のまま Promise を返り値としてすり抜けさせ、
   * `finally` が Promise の解決を待たずに即座に `process.env.TZ` を元へ戻していた。結果として
   * マウント・描画・アサーションは指定した地域ではなく元の地域で実行されており、テストは
   * 「常に緑になるが検出力が無い」状態だった。ヘルパー自体を async にし、`await fn()` の
   * 完了を待ってから復元する。
   */
  async function withSystemTz<T>(tz: string, fn: () => Promise<T>): Promise<T> {
    const original = process.env.TZ
    process.env.TZ = tz
    try {
      return await fn()
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

  it('SMLV-008: 端末TZが America/Los_Angeles（負の時差）でも見出しの年月が前月にずれない', async () => {
    // W3-a（CalendarWeekGrid.vue）が一度踏んだ罠と同根: Intl に timeZone を渡し忘れると、
    // UTC 月初として組み立てた Date が端末ローカルで再解釈される。
    //
    // [Codex 検分指摘・二巡目] Pacific/Kiritimati（UTC+14）を使うと、UTC 月初正午は
    // 現地では「同じ日の遅い時刻」にしかならず月がそもそも変わらないため、timeZone: 'UTC' を
    // 消しても失敗しない＝検出力の無い回帰テストになっていた（自己点検で確認済み）。
    // ここでは「前月へずれる」負の時差（America/Los_Angeles・標準時 UTC-8）を使う。
    // year=2026/month=1 の UTC 月初（2026-01-01T00:00:00Z）は LA では
    // 2025-12-31T16:00:00（PST）になり、timeZone を外すと日付だけでなく月自体が
    // 前月（2025年12月）へずれる境界になっている。
    await withSystemTz('America/Los_Angeles', async () => {
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
