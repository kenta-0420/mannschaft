import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftAvailabilityPage from '~/pages/my/shift-availability.vue'

/**
 * CMP-260920-0738 の根治テスト。
 *
 * `pages/my/shift-availability.vue` は取得失敗時に「空状態へフォールバックせず
 * エラー状態を出す」実装になっている（38行目のコメント・256行目の
 * `data-testid="availability-error-state"`）。しかしこの振る舞いを固定するテストが
 * 1本も無かった（`availability-error-state` / `errorLoad` で `frontend/tests` /
 * `frontend/app` を検索して0件）。2026-09-19 に「403で無言の空状態になる」という
 * 誤報が出た際、テストが無いために机上で否定しきれず実機確認が必要になった。
 *
 * 検証観点:
 *   SA-001 取得失敗時に `availability-error-state` が描画される
 *   SA-002 取得失敗時に `availability-empty-state`（空状態）は描画されない
 *   SA-003（対照）取得成功・0件時は `availability-empty-state` が描画され、
 *          エラー状態は描画されない（対照が無いと SA-001/002 は「常に error を
 *          出す」退化実装でも緑になってしまうため、対で確認する）
 */

const getAvailabilityDefaults = vi.fn()
const setAvailabilityDefaults = vi.fn()
const deleteAvailabilityDefaults = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }

vi.mock('~/composables/useShiftAvailabilityDefaultApi', () => ({
  useShiftAvailabilityDefaultApi: () => ({
    getAvailabilityDefaults,
    setAvailabilityDefaults,
    deleteAvailabilityDefaults,
  }),
}))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

const teamStoreStub = {
  myTeams: [{ id: 1, slug: 'team-1', name: 'Team 1', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'default', memberCount: 5 }],
  loading: false,
  fetchMyTeams: vi.fn(async () => {}),
}
mockNuxtImport('useTeamStore', () => () => teamStoreStub)

/**
 * ウォームアップマウント（既存の `ReservationMyWaitlistList.spec.ts` 等と同じ対処）。
 * `mountSuspended` の初回呼び出しは transform コストを既定 testTimeout（5秒）内で
 * 負担しきれず timeout することがあるため、大きい hookTimeout を持つ beforeAll で
 * 使い捨てマウントして前払いする。
 */
beforeAll(async () => {
  getAvailabilityDefaults.mockResolvedValue([])
  const warmup = await mountSuspended(ShiftAvailabilityPage)
  warmup.unmount()
})

describe('pages/my/shift-availability.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getAvailabilityDefaults.mockReset()
    notificationMock.error.mockClear()
  })

  it('SA-001: 取得失敗時に availability-error-state が描画される', async () => {
    getAvailabilityDefaults.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(ShiftAvailabilityPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="availability-error-state"]').exists()).toBe(true)
  })

  it('SA-002: 取得失敗時に空状態（availability-empty-state）へフォールバックしない', async () => {
    getAvailabilityDefaults.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(ShiftAvailabilityPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="availability-empty-state"]').exists()).toBe(false)
  })

  it('SA-003（対照）: 取得成功・0件時は空状態が出て、エラー状態は出ない', async () => {
    getAvailabilityDefaults.mockResolvedValue([])
    const wrapper = await mountSuspended(ShiftAvailabilityPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="availability-empty-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="availability-error-state"]').exists()).toBe(false)
  })
})

/** onMounted 内の非同期チェーン（fetchMyTeams → loadForTeam）を捌き切るための待機。 */
async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
