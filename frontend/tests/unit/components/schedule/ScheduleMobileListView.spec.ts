import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ScheduleMobileListView from '~/components/schedule/ScheduleMobileListView.vue'
import { useAuthStore } from '~/stores/useAuthStore'

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

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
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
})

async function mountView(props: Record<string, unknown>) {
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
})
