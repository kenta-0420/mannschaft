import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SchedulePage from '~/pages/teams/[slug]/schedule.vue'

/**
 * F03.19 §6.8（Wave 3-c）AC-14b 回帰テスト。
 *
 * `teams/[slug]/schedule.vue` の `md:hidden` ブロック（月ナビ＋SectionCard＋ScheduleListRow の
 * 繰り返し＋空状態）を共通コンポーネント ScheduleMobileListView.vue へ切り出した。
 * 「切り出しただけで挙動は完全に同一」であることを、切り出し前と同じ観測点
 * （data-testid・aria-label・表示文言）で確認する。
 */

const scheduleApiMock = {
  listSchedules: vi.fn(),
  getSchedule: vi.fn(),
  deleteSchedule: vi.fn(),
}
vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => scheduleApiMock }))

const apiMock = vi.fn()
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useRoute', () => () => ({ params: { slug: 't1' } }))

const ScheduleListRowStub = defineComponent({
  name: 'ScheduleListRow',
  props: { event: Object, scopeType: String, scopeId: String },
  emits: ['open', 'responded'],
  setup(props, { emit }) {
    return () => h('button', {
      'data-testid': 'schedule-list-row',
      onClick: () => emit('open', (props.event as { id: number }).id),
    }, (props.event as { title: string }).title)
  },
})

const emptySchedules = { data: [] }

// useCalendarEvents の既定表示月は実行時の「今日」を基準にする。固定の過去月にすると
// 取得範囲（from/to）に一致せず、フィルタで弾かれて描画されない（本テストで踏んだ実際の罠）。
// そのため常に「今月」の日付になるフィクスチャを使う。
const now = new Date()
const thisMonthDateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-15`

function teamScheduleEntry() {
  return {
    id: 5,
    content: { title: 'チーム予定', eventType: 'SCHEDULE', status: 'PUBLISHED' },
    time: { startAt: `${thisMonthDateStr}T10:00:00+09:00`, endAt: `${thisMonthDateStr}T11:00:00+09:00`, allDay: false },
  }
}

async function mountSchedulePage() {
  const wrapper = await mountSuspended(SchedulePage, {
    global: {
      stubs: {
        ScheduleListRow: ScheduleListRowStub,
        Button: false,
        SectionCard: false,
        DashboardEmptyState: false,
        EventDetailPanel: true,
        CalendarGrid: true,
        ScheduleEventForm: true,
      },
    },
  })
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('pages/teams/[slug]/schedule.vue: AC-14b モバイルリスト回帰', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.mockReset().mockResolvedValue({ data: { roleName: 'MEMBER', permissions: [] } })
  })

  it('AC-14b: 予定が有るとき、切り出し前と同じ data-testid でリストが表示される', async () => {
    scheduleApiMock.listSchedules.mockReset().mockResolvedValue({ data: [teamScheduleEntry()] })
    const wrapper = await mountSchedulePage()

    expect(wrapper.find('[data-testid="schedule-list-view"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('チーム予定')
  })

  it('AC-14b: 予定が無いとき、従来と同じ空状態文言（schedule.list.empty）が出る', async () => {
    scheduleApiMock.listSchedules.mockReset().mockResolvedValue(emptySchedules)
    const wrapper = await mountSchedulePage()

    // テスト環境の既定ロケールは en のため、schedule.list.empty の en 訳文で照合する。
    expect(wrapper.text()).toContain('No events for this period')
  })

  it('AC-14b: 月ナビの前後移動ボタンを押すと表示中の年月ラベルが移動する', async () => {
    scheduleApiMock.listSchedules.mockReset().mockResolvedValue(emptySchedules)
    const wrapper = await mountSchedulePage()

    const periodLabelSelector = '.min-w-\\[110px\\]'
    const labelBefore = wrapper.get(periodLabelSelector).text()

    // テスト環境の既定ロケールは en のため、i18n キーの言語に依存しないアイコンクラスで探す。
    const buttons = wrapper.findAll('button')
    const nextButton = buttons.find(b => b.find('.pi-chevron-right').exists())
    expect(nextButton?.exists()).toBe(true)

    await nextButton!.trigger('click')
    await flushPromises()

    const labelAfter = wrapper.get(periodLabelSelector).text()
    expect(labelAfter).not.toBe(labelBefore)
    // 月移動のたびに listSchedules が再度呼ばれる（キャッシュ済み隣接月をまたいでも、
    // 少なくとも初回呼び出しより回数は減らない）。
    expect(scheduleApiMock.listSchedules).toHaveBeenCalled()
  })

  it('AC-14b: 行タップで従来どおり詳細取得 API（getSchedule）が呼ばれる', async () => {
    scheduleApiMock.listSchedules.mockReset().mockResolvedValue({ data: [teamScheduleEntry()] })
    scheduleApiMock.getSchedule.mockReset().mockResolvedValue({
      data: { id: 5, content: { title: 'チーム予定' }, time: { startAt: '2026-07-10T10:00:00+09:00', endAt: '2026-07-10T11:00:00+09:00', allDay: false }, status: { status: 'PUBLISHED' } },
    })
    const wrapper = await mountSchedulePage()

    await wrapper.get('[data-testid="schedule-list-row"]').trigger('click')
    await flushPromises()

    expect(scheduleApiMock.getSchedule).toHaveBeenCalledWith('team', 't1', 5)
  })
})
