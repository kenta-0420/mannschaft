import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import FailedEventsPage from './failed-events.vue'
import type { FailedEventResponse } from '~/types/shiftBudget'

/**
 * CMP-260909-0544: 失敗イベント画面に、実行SQL・テーブル名・カラム名・Java例外クラス名を出さない。
 * BE の error_message は `例外クラス名: Hibernate メッセージ(実行SQL全文)` を含むため、画面は
 * 生メッセージを描画せず、種別に応じた説明と識別子だけを出す。API境界を制御するFE試験であり実機証拠ではない。
 */
const SQL = 'select sbae1_0.id,sbae1_0.organization_id from shift_budget_allocation_entries sbae1_0 where sbae1_0.fiscal_year_id=?'
const RAW = `InvalidDataAccessResourceUsageException: JDBC exception executing SQL [${SQL}] [Unknown column 'sbae1_0.x']`

const listFailedEvents = vi.fn<(...args: unknown[]) => Promise<FailedEventResponse[]>>()

mockNuxtImport('useShiftBudgetApi', () => () => ({
  listFailedEvents,
  retryFailedEvent: vi.fn(),
  resolveFailedEvent: vi.fn(),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: 5 } }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (v: string | null) => v ?? '-' }))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({
    canManageBudget: { value: false },
    ensureLoaded: async () => undefined,
  }),
}))

function event(id: number, type: string, message: string | null): FailedEventResponse {
  return {
    id, organization_id: 5, event_type: type, source_id: 77, error_message: message,
    retry_count: 1, last_retried_at: null, status: 'PENDING',
    created_at: '2026-09-09T10:00:00', updated_at: '2026-09-09T10:00:00',
  }
}

describe('/admin/shift-budget/failed-events の内部情報非表示', () => {
  beforeEach(() => {
    listFailedEvents.mockResolvedValue([
      event(101, 'CONSUMPTION_RECORD', RAW),
      event(102, 'NOTIFICATION_SEND', 'java.lang.NullPointerException: Cannot invoke "String.length()"'),
    ])
  })

  it('SQL文・テーブル名・カラム名・例外クラス名がDOMに出ない', async () => {
    const wrapper = await mountSuspended(FailedEventsPage)
    await flushPromises()
    const html = wrapper.html()
    const text = wrapper.text()
    for (const leaked of [
      'InvalidDataAccessResourceUsageException', 'NullPointerException', 'java.lang', 'JDBC',
      'select sbae1_0', 'shift_budget_allocation_entries', 'organization_id', 'fiscal_year_id',
    ]) {
      expect(html).not.toContain(leaked)
    }
    // 問い合わせ用の識別子（イベントID・対象ID）は残す
    expect(text).toContain('101')
    expect(text).toContain('102')
    expect(text).toContain('77')
  })

  it('生の種別コード（CONSUMPTION_RECORD 等）も出さない', async () => {
    const wrapper = await mountSuspended(FailedEventsPage)
    await flushPromises()
    expect(wrapper.text()).not.toContain('CONSUMPTION_RECORD')
    expect(wrapper.text()).not.toContain('NOTIFICATION_SEND')
  })
})
