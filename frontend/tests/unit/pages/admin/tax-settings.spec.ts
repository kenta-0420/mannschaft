import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TaxSettingsPage from '~/pages/admin/tax-settings.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/admin/tax-settings.vue` は税率一覧の取得失敗を握りつぶし、`taxSettings` を
 * 空配列にリセットするだけで「税率が登録されていません」の空状態へ落ちていた。
 * エラー専用状態（DashboardErrorState / tax-settings-error-state）で修正した。
 *
 * 検証観点:
 *   TS-001 取得失敗時に tax-settings-error-state が描画される
 *   TS-002（対照）取得成功・0件時はエラー状態を出さない
 */

const getTaxSettings = vi.fn()

vi.mock('~/composables/useTaxSettingApi', () => ({
  useTaxSettingApi: () => ({
    getTaxSettings,
    createTaxSetting: vi.fn(),
    updateTaxSetting: vi.fn(),
    deleteTaxSetting: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

beforeAll(async () => {
  getTaxSettings.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(TaxSettingsPage)
  warmup.unmount()
})

describe('pages/admin/tax-settings.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getTaxSettings.mockReset()
    notificationMock.error.mockClear()
  })

  it('TS-001: 取得失敗時に tax-settings-error-state が描画される', async () => {
    getTaxSettings.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TaxSettingsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="tax-settings-error-state"]').exists()).toBe(true)
  })

  it('TS-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getTaxSettings.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(TaxSettingsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="tax-settings-error-state"]').exists()).toBe(false)
  })

  it('TS-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    getTaxSettings.mockRejectedValueOnce(new Error('network error'))
    getTaxSettings.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(TaxSettingsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="tax-settings-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="tax-settings-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getTaxSettings).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="tax-settings-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
