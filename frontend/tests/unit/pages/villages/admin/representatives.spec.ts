import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import RepresentativesPage from '~/pages/villages/[id]/admin/representatives.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/admin/representatives.vue` は代表委任一覧の取得失敗時に
 * `representatives` を空配列にリセットするだけで、PrimeVue DataTable の #empty スロット
 * をそのまま描画していた。エラー専用状態（DashboardErrorState / representative-error-state）
 * で修正した。委任先候補（`teamOrgMemberships`、補助データ）の取得失敗は既存方針どおり
 * 一覧表示自体を止めない（対象外・変更なし）。
 *
 * 検証観点:
 *   RP-001 主データ（listRepresentatives）取得失敗時に representative-error-state が描画される
 *   RP-002（対照）取得成功・0件時はエラー状態を出さない
 *   RP-003 再試行が初回表示と同じ取得関数（loadRepresentatives）を呼ぶ
 */

const listRepresentatives = vi.fn()
const revokeRepresentative = vi.fn()
const listMembers = vi.fn()

vi.mock('~/composables/village/useVillageFeatureApi', () => ({
  useVillageFeatureApi: () => ({ listRepresentatives, revokeRepresentative }),
}))
vi.mock('~/composables/village/useVillageMembershipApi', () => ({
  useVillageMembershipApi: () => ({ listMembers }),
}))

vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: ref({ id: 'v1', name: 'テスト村' }),
    perms: ref({ isMember: true, isAdmin: true, isHeadman: true, myRole: 'HEADMAN' }),
    currentUserId: ref(1),
    refresh: vi.fn(async () => {}),
    myMembership: ref(null),
    openEditDialog: vi.fn(),
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn(), showSuccess: vi.fn(), showError: vi.fn(), showWarn: vi.fn(), showInfo: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listRepresentatives.mockResolvedValue([])
  listMembers.mockResolvedValue({ content: [] })
  const warmup = await mountSuspended(RepresentativesPage)
  warmup.unmount()
})

describe('pages/villages/[id]/admin/representatives.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listRepresentatives.mockReset()
    listMembers.mockReset()
    listMembers.mockResolvedValue({ content: [] })
    notificationMock.showError.mockClear()
  })

  it('RP-001: 取得失敗時に representative-error-state が描画される', async () => {
    listRepresentatives.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(RepresentativesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="representative-error-state"]').exists()).toBe(true)
  })

  it('RP-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listRepresentatives.mockResolvedValue([])
    const wrapper = await mountSuspended(RepresentativesPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="representative-error-state"]').exists()).toBe(false)
  })

  it('RP-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listRepresentatives.mockRejectedValueOnce(new Error('network error'))
    listRepresentatives.mockResolvedValueOnce([
      { id: 'r1', membershipId: 'm1', representativeUserId: 2, grantedByUserId: 1, grantedAt: '2026-01-01T00:00:00Z' },
    ])
    const wrapper = await mountSuspended(RepresentativesPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="representative-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="representative-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listRepresentatives).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="representative-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
