import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ChroniclesPage from '~/pages/villages/[id]/chronicles.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/chronicles.vue`（村史）は行事アーカイブの取得失敗時に
 * `archives` を空配列にリセットするだけで、空状態をそのまま描画していた。
 * エラー専用状態（DashboardErrorState / village-chronicles-error-state）で修正した。
 *
 * 検証観点:
 *   CH-001 取得失敗時に village-chronicles-error-state が描画される
 *   CH-002（対照）取得成功・0件時はエラー状態を出さない
 *   CH-003 再試行が初回表示と同じ取得関数（loadArchives）を呼ぶ
 */

const listEventArchives = vi.fn()

vi.mock('~/composables/useVillageApi', () => ({
  useVillageApi: () => ({ listEventArchives }),
}))

vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: { value: { id: 'v1', name: 'テスト村' } },
    perms: { value: { isMember: true, isAdmin: false, isHeadman: false, myRole: 'VILLAGER' } },
    currentUserId: { value: 1 },
    refresh: vi.fn(async () => {}),
    myMembership: { value: null },
    openEditDialog: vi.fn(),
  }),
}))

const errorHandlerMock = { handleApiError: vi.fn() }
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => errorHandlerMock }))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listEventArchives.mockResolvedValue([])
  const warmup = await mountSuspended(ChroniclesPage)
  warmup.unmount()
})

describe('pages/villages/[id]/chronicles.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listEventArchives.mockReset()
    errorHandlerMock.handleApiError.mockClear()
  })

  it('CH-001: 取得失敗時に village-chronicles-error-state が描画される', async () => {
    listEventArchives.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(ChroniclesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-chronicles-error-state"]').exists()).toBe(true)
  })

  it('CH-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listEventArchives.mockResolvedValue([])
    const wrapper = await mountSuspended(ChroniclesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-chronicles-error-state"]').exists()).toBe(false)
  })

  it('CH-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listEventArchives.mockRejectedValueOnce(new Error('network error'))
    listEventArchives.mockResolvedValueOnce([
      { id: 'a1', title: 'X', sourceType: 'FESTIVAL', archivedAt: '2026-01-01T00:00:00Z' },
    ])
    const wrapper = await mountSuspended(ChroniclesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="village-chronicles-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="village-chronicles-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listEventArchives).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="village-chronicles-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
