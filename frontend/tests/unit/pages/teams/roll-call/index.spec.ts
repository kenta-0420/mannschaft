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
 *
 * 【検分差し戻し対応】候補取得（主データ）の成否を、補助情報（事前連絡 /
 * getAdvanceNotices）の完了を待たずに反映することの根治テスト。
 * 修正前は `Promise.allSettled([loadCandidates(), loadAdvanceNotices()])` の
 * 完了を待ってから loadFailed を決めていたため、補助情報が終わらない間は
 * 候補取得の成否が画面に反映されなかった（初回: 候補が失敗していてもエラー状態が
 * 出ない／再試行: 候補が成功してもエラー画面から戻れない）。
 *   RC-004 補助情報が解決しない状態で候補取得が失敗 → 補助情報を待たずに
 *          roll-call-error-state が描画される
 *   RC-005 同じ状態で再試行し候補取得が成功 → 補助情報を待たずにエラー状態が消え、
 *          roll-call-empty（候補0件の通常空状態）が出る
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

  it('RC-004: 補助情報が解決しない状態で候補取得が失敗 → 補助情報を待たずにエラー状態が出る', async () => {
    // getAdvanceNotices は意図的に resolve/reject させない（止まっている状態を模す）
    getAdvanceNotices.mockReturnValue(new Promise(() => {}))
    getCandidates.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(RollCallPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="roll-call-empty"]').exists()).toBe(false)
  })

  it('RC-005: 同じ状態で再試行し候補取得が成功 → 補助情報を待たずにエラー状態が消える', async () => {
    getAdvanceNotices.mockReturnValue(new Promise(() => {}))
    getCandidates.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(RollCallPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(true)

    getCandidates.mockResolvedValueOnce([])
    const retryButton = wrapper.find('[data-testid="roll-call-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)
    await retryButton.trigger('click')
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="roll-call-error-state"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="roll-call-empty"]').exists()).toBe(true)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
