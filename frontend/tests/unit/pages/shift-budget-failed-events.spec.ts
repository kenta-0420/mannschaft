import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import FailedEvents from '~/pages/admin/shift-budget/failed-events.vue'
import type { FailedEventResponse, FailedEventStatus } from '~/types/shiftBudget'

const state = vi.hoisted(() => ({
  admin: true,
  events: [] as FailedEventResponse[],
  retry: vi.fn(),
  resolve: vi.fn(),
}))

mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: 42 } }))
mockNuxtImport('useShiftBudgetApi', () => () => ({
  listFailedEvents: vi.fn(async () => state.events),
  retryFailedEvent: state.retry,
  resolveFailedEvent: state.resolve,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (value: string | null) => value ?? '-' }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: computed(() => state.admin), ensureLoaded: vi.fn() }),
}))

function event(id: number, type: string, status: FailedEventStatus = 'PENDING'): FailedEventResponse {
  return {
    id, organization_id: 42, event_type: type, status,
    source_id: null, error_message: '元の診断', retry_count: 0, last_retried_at: null,
    created_at: '2026-01-01T00:00:00', updated_at: '2026-01-01T00:00:00',
  }
}

describe('CMP-260910-1606 失敗イベントの操作導線', () => {
  beforeEach(() => {
    state.admin = true
    state.events = []
    vi.clearAllMocks()
  })

  it('既知の対応3種だけ再実行を提供しEXHAUSTEDは許可する', async () => {
    state.events = [
      event(1001, 'THRESHOLD_ALERT'), event(1002, 'WORKFLOW_START'),
      event(1003, 'NOTIFICATION_SEND', 'EXHAUSTED'),
      event(1004, 'CONSUMPTION_RECORD'), event(1005, 'CONSUMPTION_CANCEL'),
      event(1006, 'FUTURE_TYPE'), event(1007, ''),
      event(1008, 'THRESHOLD_ALERT', 'SUCCEEDED'), event(1009, 'NOTIFICATION_SEND', 'MANUAL_RESOLVED'),
      event(1010, null as unknown as string),
    ]
    const wrapper = await mountSuspended(FailedEvents)
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(10)
    for (const row of rows) {
      const id = Number(row.find('td').text())
      const retries = row.findAll('button').filter(button => button.text() === 'shiftBudget.failedEvent.retry')
      expect(retries, `ID:${id}: ${row.html()}`).toHaveLength(id <= 1003 ? 1 : 0)
      if (id === 1008) {
        expect(row.text()).toContain('shiftBudget.failedEvent.statusValue.SUCCEEDED')
        expect(row.find('.p-tag').classes()).toContain('p-tag-success')
      }
      if (id === 1004 || id === 1005) {
        expect(row.text()).toContain('元の診断')
        expect(row.findAll('button').some(button => button.text() === 'shiftBudget.failedEvent.resolve')).toBe(true)
      }
    }
    expect(state.retry).not.toHaveBeenCalled()
  })

  it('権限なしでは対応種にも管理操作を提供しない', async () => {
    state.admin = false
    state.events = [event(1001, 'THRESHOLD_ALERT')]
    const wrapper = await mountSuspended(FailedEvents)
    await flushPromises()
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
  })

  it('空一覧でも再実行を送らない', async () => {
    const wrapper = await mountSuspended(FailedEvents)
    await flushPromises()
    expect(wrapper.findAll('tbody button')).toHaveLength(0)
    expect(state.retry).not.toHaveBeenCalled()
  })
})
