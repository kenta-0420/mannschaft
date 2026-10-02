import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import IncidentBannersPage from '~/pages/system-admin/incident-banners/index.vue'

/**
 * CMP-260922-2045 第2陣 G4 の根治テスト。
 *
 * `pages/system-admin/incident-banners/index.vue` はバナー一覧の取得失敗時に
 * トーストは出すものの、`banners` を空配列にリセットするだけで「登録された
 * バナーはありません」の空状態へ落ちていた。エラー専用状態（DashboardErrorState /
 * incident-banners-error-state）で修正した。検知候補（suggestions）は補助データで
 * 別セクション・折りたたみ式のため対象外。
 *
 * 検証観点:
 *   IB-001 取得失敗時に incident-banners-error-state が描画される
 *   IB-002（対照）取得成功・0件時はエラー状態を出さない
 */

const fetchList = vi.fn()
const fetchSuggestions = vi.fn()

vi.mock('~/composables/useIncidentBannerAdmin', () => ({
  useIncidentBannerAdmin: () => ({
    fetchList,
    fetchSuggestions,
    createBanner: vi.fn(),
    updateBanner: vi.fn(),
    publishBanner: vi.fn(),
    unpublishBanner: vi.fn(),
    deleteBanner: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true }))

beforeAll(async () => {
  fetchList.mockResolvedValue({ data: [], meta: { total: 0 } })
  const warmup = await mountSuspended(IncidentBannersPage)
  warmup.unmount()
})

describe('pages/system-admin/incident-banners/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    fetchList.mockReset()
    notificationMock.error.mockClear()
  })

  it('IB-001: 取得失敗時に incident-banners-error-state が描画される', async () => {
    fetchList.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(IncidentBannersPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="incident-banners-error-state"]').exists()).toBe(true)
  })

  it('IB-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    fetchList.mockResolvedValue({ data: [], meta: { total: 0 } })
    const wrapper = await mountSuspended(IncidentBannersPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="incident-banners-error-state"]').exists()).toBe(false)
  })

  it('IB-003: 再試行は初回と同じ load() を呼ぶ', async () => {
    fetchList.mockRejectedValueOnce(new Error('network error'))
    fetchList.mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    const wrapper = await mountSuspended(IncidentBannersPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="incident-banners-error-state"]').exists()).toBe(true)
    await wrapper.find('[data-testid="incident-banners-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(fetchList).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="incident-banners-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
