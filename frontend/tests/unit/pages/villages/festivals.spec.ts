import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import FestivalsPage from '~/pages/villages/[id]/festivals.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/festivals.vue` はお祭り一覧の取得失敗時に `festivals` を
 * 空配列にリセットするだけで、子コンポーネント `VillageFestivalListSection` が
 * 空状態をそのまま描画していた。エラー専用状態
 * （DashboardErrorState / village-festivals-error-state）で修正した。
 *
 * 検証観点:
 *   FE-001 取得失敗時に village-festivals-error-state が描画される
 *   FE-002（対照）取得成功・0件時はエラー状態を出さない
 *   FE-003 再試行が初回表示と同じ取得関数（loadFestivals）を呼ぶ
 */

const listFestivals = vi.fn()

vi.mock('~/composables/useVillageApi', () => ({
  useVillageApi: () => ({ listFestivals }),
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

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

vi.mock('~/composables/useConfirmDialog', () => ({
  useConfirmDialog: () => ({ confirmAction: vi.fn() }),
}))

vi.mock('~/composables/useTimelineApi', () => ({
  useTimelineApi: () => ({ createPost: vi.fn(async () => ({ data: { id: 1 } })) }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listFestivals.mockResolvedValue([])
  const warmup = await mountSuspended(FestivalsPage)
  warmup.unmount()
})

describe('pages/villages/[id]/festivals.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listFestivals.mockReset()
    errorHandlerMock.handleApiError.mockClear()
  })

  it('FE-001: 取得失敗時に village-festivals-error-state が描画される', async () => {
    listFestivals.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(FestivalsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-festivals-error-state"]').exists()).toBe(true)
  })

  it('FE-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listFestivals.mockResolvedValue([])
    const wrapper = await mountSuspended(FestivalsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-festivals-error-state"]').exists()).toBe(false)
  })

  it('FE-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listFestivals.mockRejectedValueOnce(new Error('network error'))
    listFestivals.mockResolvedValueOnce([
      { id: 'f1', title: 'X', status: 'ACTIVE', startsAt: '2026-01-01T00:00:00Z', endsAt: '2026-01-02T00:00:00Z' },
    ])
    const wrapper = await mountSuspended(FestivalsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="village-festivals-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="village-festivals-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listFestivals).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="village-festivals-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
