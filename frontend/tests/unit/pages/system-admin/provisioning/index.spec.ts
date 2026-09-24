import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ProvisioningPage from '~/pages/system-admin/provisioning/index.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/system-admin/provisioning/index.vue` は招待一覧の取得失敗時にトーストは
 * 出すものの、`invitations` を空配列にリセットするだけで「招待がありません」の
 * 空状態へ落ちていた。エラー専用状態（DashboardErrorState / provisioning-error-state）
 * で修正した。
 *
 * 検証観点:
 *   PV-001 取得失敗時に provisioning-error-state が描画される
 *   PV-002（対照）取得成功・0件時はエラー状態を出さない
 */

const list = vi.fn()

vi.mock('~/composables/useProvisioningAdminApi', () => ({
  useProvisioningAdminApi: () => ({
    list,
    createOrganization: vi.fn(),
    createTeam: vi.fn(),
    resend: vi.fn(),
    cancel: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: vi.fn() }),
}))

mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true }))

beforeAll(async () => {
  list.mockResolvedValue([])
  const warmup = await mountSuspended(ProvisioningPage)
  warmup.unmount()
})

describe('pages/system-admin/provisioning/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    list.mockReset()
    notificationMock.error.mockClear()
  })

  it('PV-001: 取得失敗時に provisioning-error-state が描画される', async () => {
    list.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(ProvisioningPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="provisioning-error-state"]').exists()).toBe(true)
  })

  it('PV-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    list.mockResolvedValue([])
    const wrapper = await mountSuspended(ProvisioningPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="provisioning-error-state"]').exists()).toBe(false)
  })

  it('PV-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    list.mockRejectedValueOnce(new Error('network error'))
    list.mockResolvedValueOnce([])
    const wrapper = await mountSuspended(ProvisioningPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="provisioning-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="provisioning-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="provisioning-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
