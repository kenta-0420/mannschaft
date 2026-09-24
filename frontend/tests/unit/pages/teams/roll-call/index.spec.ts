import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import RollCallPage from '~/pages/teams/[slug]/events/[eventId]/roll-call.vue'

/**
 * CMP-260922-2045 第2陣 G1 の根治テスト。
 *
 * `pages/teams/[slug]/events/[eventId]/roll-call.vue` は点呼候補（主データ）の取得が
 * 失敗しても `candidates` が空配列のまま {@link RollCallSheet} へ渡り、共通コンポーネント側の
 * 「対象者がいません」（`roll-call-empty` / event.rollCall.noCandidates）へそのまま落ちていた。
 * 権限エラー・通信断が「対象者なし」に誤読される欠陥を、エラー専用状態
 * （DashboardErrorState / roll-call-error-state）で修正した。
 *
 * 検証観点:
 *   RC-001 点呼候補取得失敗時に roll-call-error-state が描画される
 *   RC-002（対照）取得成功・0件時は roll-call-empty（RollCallSheet 側の空状態）が出て、
 *          エラー状態は出ない
 *   RC-003 再試行は初回表示と同じ取得関数（getCandidates）を呼ぶ
 */

const getCandidates = vi.fn()
const getSessions = vi.fn()
const submitRollCall = vi.fn()
const patchEntry = vi.fn()
const getAdvanceNotices = vi.fn()

vi.mock('~/composables/useRollCallApi', () => ({
  useRollCallApi: () => ({ getCandidates, getSessions, submitRollCall, patchEntry }),
}))
vi.mock('~/composables/useAdvanceNoticeApi', () => ({
  useAdvanceNoticeApi: () => ({ getAdvanceNotices }),
}))
vi.mock('~/composables/jobs/useOfflineCareQueue', () => ({
  useOfflineCareQueue: () => ({
    enqueueCareJob: vi.fn(async () => {}),
    flushPendingCareJobs: vi.fn(async () => {}),
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1', eventId: '10' } }))

beforeAll(async () => {
  getCandidates.mockResolvedValue([])
  getAdvanceNotices.mockResolvedValue([])
  const warmup = await mountSuspended(RollCallPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/events/[eventId]/roll-call.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getCandidates.mockReset()
    getAdvanceNotices.mockReset()
    getAdvanceNotices.mockResolvedValue([])
  })

  it('RC-001: 点呼候補取得失敗時に roll-call-error-state が描画される', async () => {
    getCandidates.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(RollCallPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="roll-call-empty"]').exists()).toBe(false)
  })

  it('RC-002（対照）: 取得成功・0件時は roll-call-empty が出てエラー状態は出ない', async () => {
    getCandidates.mockResolvedValue([])
    const wrapper = await mountSuspended(RollCallPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="roll-call-empty"]').exists()).toBe(true)
  })

  it('RC-003: 再試行は初回表示と同じ取得関数（getCandidates）を呼ぶ', async () => {
    getCandidates.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(RollCallPage)
    await flushMicrotasks()
    expect(getCandidates).toHaveBeenCalledTimes(1)

    getCandidates.mockResolvedValueOnce([])
    const retryButton = wrapper.find('[data-testid="roll-call-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(getCandidates).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
