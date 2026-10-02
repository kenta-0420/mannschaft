import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import LeagueTransfersPage from '~/pages/organizations/[slug]/league-transfers/index.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/organizations/[slug]/league-transfers/index.vue` の送り出し候補一覧
 * （getCandidates）は取得失敗時も `candidates` を空配列に戻さず通知するだけで、
 * 昇格・降格それぞれの空状態文言（「候補はいません」等）へそのまま落ちていた。
 * エラー専用状態（DashboardErrorState / league-transfer-candidates-error-state）
 * で修正した。
 *
 * 検証観点:
 *   LTC-001 大会選択後、候補取得失敗時に league-transfer-candidates-error-state が描画される
 *   LTC-002（対照）取得成功・0件時はエラー状態を出さない
 *   LTC-003 再試行は初回と同じ取得処理（getCandidates）を呼ぶ
 */

const getTournaments = vi.fn()
const getCandidates = vi.fn()
const getDivisions = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
vi.mock('~/composables/tournament/useLeagueTransfer', () => ({
  useLeagueTransfer: () => ({
    getCandidates,
    getInboundTransfers: vi.fn(async () => ({ data: [] })),
    promote: vi.fn(),
    relegate: vi.fn(),
    approve: vi.fn(),
    decline: vi.fn(),
    cancel: vi.fn(),
  }),
}))
vi.mock('~/composables/tournament/useTournamentBase', () => ({
  useTournamentBase: () => ({
    getTournaments,
    getDivisions,
  }),
}))

const tournaments = [{ id: 1, title: '第1回大会' }]

beforeAll(async () => {
  getTournaments.mockResolvedValue({ data: tournaments })
  getCandidates.mockResolvedValue({ data: [] })
  getDivisions.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(LeagueTransfersPage)
  warmup.unmount()
})

async function selectTournament(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  const select = wrapper.findComponent({ name: 'Select' })
  await select.vm.$emit('update:modelValue', 1)
  await flushMicrotasks()
}

describe('pages/organizations/[slug]/league-transfers/index.vue — 候補取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getTournaments.mockReset()
    getCandidates.mockReset()
    getDivisions.mockReset()
    getTournaments.mockResolvedValue({ data: tournaments })
    getDivisions.mockResolvedValue({ data: [] })
  })

  it('LTC-001: 候補取得失敗時に league-transfer-candidates-error-state が描画される', async () => {
    getCandidates.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await selectTournament(wrapper)

    expect(wrapper.find('[data-testid="league-transfer-candidates-error-state"]').exists()).toBe(true)
  })

  it('LTC-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getCandidates.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await selectTournament(wrapper)

    expect(wrapper.find('[data-testid="league-transfer-candidates-error-state"]').exists()).toBe(false)
  })

  it('LTC-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    getCandidates.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await selectTournament(wrapper)
    expect(wrapper.find('[data-testid="league-transfer-candidates-error-state"]').exists()).toBe(true)

    getCandidates.mockResolvedValueOnce({ data: [] })
    const callsBefore = getCandidates.mock.calls.length
    await wrapper.find('[data-testid="league-transfer-candidates-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getCandidates.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="league-transfer-candidates-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
