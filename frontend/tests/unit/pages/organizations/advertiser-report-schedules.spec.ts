import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import OrgReportSchedulesPage from '~/pages/organizations/[slug]/advertiser/report-schedules.vue'

/**
 * CMP-260922-2045 第2陣 G5 の根治テスト。
 *
 * `pages/organizations/[slug]/advertiser/report-schedules.vue` は定期レポート一覧の
 * 取得が失敗しても `schedules` を空配列にリセットするだけで、「定期レポートはまだ設定
 * されていません。」という空状態文言へそのまま落ちていた。権限エラー・通信断が
 * 「未設定」に誤読される欠陥を、エラー専用状態（DashboardErrorState / report-schedules-error-state）
 * で修正した。
 *
 * 検証観点:
 *   OR-001 取得失敗時に report-schedules-error-state が描画される
 *   OR-002（対照）取得成功・0件時はエラー状態を出さない
 *   OR-003 再試行が初回表示と同じ取得関数（load）を呼ぶ
 */

const getReportSchedules = vi.fn()

vi.mock('~/composables/useAdvertiserApi', () => ({
  useAdvertiserApi: () => ({
    getReportSchedules,
    createReportSchedule: vi.fn(),
    deleteReportSchedule: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))

beforeAll(async () => {
  getReportSchedules.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(OrgReportSchedulesPage)
  warmup.unmount()
})

describe('pages/organizations/[slug]/advertiser/report-schedules.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getReportSchedules.mockReset()
  })

  it('OR-001: 取得失敗時に report-schedules-error-state が描画される', async () => {
    getReportSchedules.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(OrgReportSchedulesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="report-schedules-error-state"]').exists()).toBe(true)
  })

  it('OR-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getReportSchedules.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(OrgReportSchedulesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="report-schedules-error-state"]').exists()).toBe(false)
  })

  it('OR-003: 再試行が初回表示と同じ取得処理（getReportSchedules）を呼ぶ', async () => {
    getReportSchedules.mockRejectedValueOnce(new Error('network error'))
    getReportSchedules.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(OrgReportSchedulesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="report-schedules-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="report-schedules-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getReportSchedules).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="report-schedules-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
