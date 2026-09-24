import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import MatchRecruitsPage from '~/pages/villages/[id]/match-recruits.vue'
import MatchRecruitList from '~/components/match-recruits/MatchRecruitList.vue'

/**
 * CMP-260922-2045 第2陣 G2 の根治テスト。
 *
 * `pages/villages/[id]/match-recruits.vue` は募集一覧の取得失敗時に `recruits` を
 * 空配列にリセットするだけで、子コンポーネント `MatchRecruitList` が空状態
 * （「募集はありません」）をそのまま描画していた（権限エラー・通信断が「募集なし」に
 * 誤読される）。エラー専用状態（DashboardErrorState / village-match-recruits-error-state）
 * で修正した。
 *
 * 検証観点:
 *   MR-001 取得失敗時に village-match-recruits-error-state が描画される
 *   MR-002（対照）取得成功・0件時はエラー状態を出さない
 *   MR-003 再試行が初回表示と同じ取得関数（loadRecruits）を呼ぶ
 *   MR-004（Codex 検分差し戻し・世代ガード）フィルタ連打で取得が重なり、
 *          新しい取得が成功した直後に古い取得が失敗で返っても、
 *          最新の一覧が表示されエラー状態に隠れない
 */

const listMatchRecruits = vi.fn()

vi.mock('~/composables/useVillageApi', () => ({
  useVillageApi: () => ({ listMatchRecruits }),
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

mockNuxtImport('useRoute', () => () => ({ params: { id: 'v1' } }))

beforeAll(async () => {
  listMatchRecruits.mockResolvedValue({ items: [], page: 0, size: 50, total: 0 })
  const warmup = await mountSuspended(MatchRecruitsPage)
  warmup.unmount()
})

describe('pages/villages/[id]/match-recruits.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listMatchRecruits.mockReset()
    errorHandlerMock.handleApiError.mockClear()
  })

  it('MR-001: 取得失敗時に village-match-recruits-error-state が描画される', async () => {
    listMatchRecruits.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(MatchRecruitsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-match-recruits-error-state"]').exists()).toBe(true)
  })

  it('MR-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listMatchRecruits.mockResolvedValue({ items: [], page: 0, size: 50, total: 0 })
    const wrapper = await mountSuspended(MatchRecruitsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="village-match-recruits-error-state"]').exists()).toBe(false)
  })

  it('MR-003: 再試行が初回表示と同じ取得関数を呼ぶ（成功に回復できる）', async () => {
    listMatchRecruits.mockRejectedValueOnce(new Error('network error'))
    listMatchRecruits.mockResolvedValueOnce({
      items: [{ id: 'r1', category: 'PRACTICE_MATCH', status: 'OPEN', title: 'X', postedByUserId: 1 }],
      page: 0,
      size: 50,
      total: 1,
    })
    const wrapper = await mountSuspended(MatchRecruitsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="village-match-recruits-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="village-match-recruits-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listMatchRecruits).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="village-match-recruits-error-state"]').exists()).toBe(false)
  })

  it('MR-004（世代ガード）: 新しい取得が成功した直後に古い取得が失敗で返っても、最新の一覧を隠さない', async () => {
    // 初回マウント分を片付ける
    listMatchRecruits.mockResolvedValueOnce({ items: [], page: 0, size: 50, total: 0 })
    const wrapper = await mountSuspended(MatchRecruitsPage)
    await flushMicrotasks()

    let rejectOld!: (e: unknown) => void
    const oldPromise = new Promise((_resolve, reject) => { rejectOld = reject })
    let resolveNew!: (v: unknown) => void
    const newPromise = new Promise((resolve) => { resolveNew = resolve })

    // フィルタを連打したことを模す: 1回目（古い・遅い・後で失敗）→ 2回目（新しい・速い・先に成功）
    listMatchRecruits.mockReturnValueOnce(oldPromise)
    listMatchRecruits.mockReturnValueOnce(newPromise)

    const list = wrapper.findComponent(MatchRecruitList)
    await list.vm.$emit('update:statusFilter', 'CLOSED')
    await list.vm.$emit('update:statusFilter', 'OPEN')
    await flushMicrotasks()

    // 新しい（2回目の）取得が先に成功で返る
    resolveNew({
      items: [{ id: 'r-new', category: 'PRACTICE_MATCH', status: 'OPEN', title: '最新の募集', postedByUserId: 1 }],
      page: 0,
      size: 50,
      total: 1,
    })
    await flushMicrotasks()

    // 古い（1回目の）取得が後から失敗で返る
    rejectOld(new Error('stale network error'))
    await flushMicrotasks()

    // 古い失敗応答に上書きされず、最新の一覧のままエラー状態は出ない
    expect(wrapper.find('[data-testid="village-match-recruits-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('最新の募集')
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
