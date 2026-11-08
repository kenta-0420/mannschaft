// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Widget from './ScopeActionRequiredWidget.vue'

const { getActionRequired } = vi.hoisted(() => ({ getActionRequired: vi.fn() }))
vi.mock('~/composables/useScopeTabApi', () => ({ useScopeTabApi: () => ({ getActionRequired }) }))

function render(sections: { circulation: boolean; survey: boolean; attendance: boolean }) {
  return mount(Widget, {
    props: { scopeType: 'TEAM', scopeId: 'team-a', sections },
    global: {
      mocks: {
        $t: (key: string, values?: { count?: number }) =>
          key + (values?.count === undefined ? '' : ':' + values.count),
      },
      stubs: {
        SectionCard: { template: '<section><slot /></section>' },
        DashboardEmptyState: true,
        Message: true,
        CirculationConfirmModal: true,
        SurveyAnswerModal: true,
        AttendanceQuickModal: true,
      },
    },
  })
}

describe('ScopeActionRequiredWidget 区分表示', () => {
  beforeEach(() => {
    getActionRequired.mockReset().mockResolvedValue({
      circulation: {
        unconfirmedCount: 4,
        items: [{ id: 'c', title: '回覧', circulatedAt: '', deadline: null }],
      },
      survey: { unansweredCount: 2, items: [{ id: 5, title: '調査', deadline: null }] },
      attendance: {
        unansweredCount: 3,
        items: [{ scheduleId: 8, eventTitle: '予定', startsAt: '' }],
      },
      totalActionCount: 9,
    })
  })

  it('可視区分だけ件数とリンクを出す', async () => {
    const wrapper = render({ circulation: false, survey: true, attendance: false })
    await (wrapper.vm as unknown as { fetchSummary: () => Promise<void> }).fetchSummary()
    await flushPromises()
    expect(wrapper.text()).toContain('unanswered:2')
    expect(wrapper.text()).not.toContain('unconfirmed:4')
    expect(wrapper.find('[data-testid="action-required-circulation-c"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="action-required-survey-5"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="action-required-attendance-8"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('隠れた区分に件数が残っていても可視区分が0なら空状態にする', async () => {
    getActionRequired.mockResolvedValueOnce({
      circulation: { unconfirmedCount: 4, items: [] },
      survey: { unansweredCount: 0, items: [] },
      attendance: { unansweredCount: 3, items: [] },
      totalActionCount: 7,
    })
    const wrapper = render({ circulation: false, survey: true, attendance: false })
    await (wrapper.vm as unknown as { fetchSummary: () => Promise<void> }).fetchSummary()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(true)
    wrapper.unmount()
  })
})
