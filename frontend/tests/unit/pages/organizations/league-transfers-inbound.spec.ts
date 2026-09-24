import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import LeagueTransfersPage from '~/pages/organizations/[slug]/league-transfers/index.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/organizations/[slug]/league-transfers/index.vue` の受信箱一覧
 * （getInboundTransfers）は取得失敗時も `inboundTransfers` を空配列に
 * リセットするだけで、空状態文言（「送り出しはありません」等）へそのまま
 * 落ちていた。エラー専用状態（DashboardErrorState /
 * league-transfer-inbound-error-state）で修正した。
 *
 * 検証観点:
 *   LTI-001 受信箱タブで取得失敗時に league-transfer-inbound-error-state が描画される
 *   LTI-002（対照）取得成功・0件時はエラー状態を出さない
 *   LTI-003 再試行は初回と同じ取得処理（getInboundTransfers）を呼ぶ
 */

const getTournaments = vi.fn()
const getInboundTransfers = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
vi.mock('~/composables/tournament/useLeagueTransfer', () => ({
  useLeagueTransfer: () => ({
    getCandidates: vi.fn(async () => ({ data: [] })),
    getInboundTransfers,
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
    getDivisions: vi.fn(async () => ({ data: [] })),
  }),
}))

beforeAll(async () => {
  getTournaments.mockResolvedValue({ data: [] })
  getInboundTransfers.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(LeagueTransfersPage)
  warmup.unmount()
})

async function openInboundTab(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  // タブ切替ボタンは先頭2つ（送り出し・受信箱）の固定順。2番目が受信箱タブ。
  const buttons = wrapper.findAll('button')
  await buttons[1]!.trigger('click')
  await flushMicrotasks()
}

describe('pages/organizations/[slug]/league-transfers/index.vue — 受信箱取得失敗時のエラー状態', () => {
  beforeEach(() => {
    getTournaments.mockReset()
    getInboundTransfers.mockReset()
    getTournaments.mockResolvedValue({ data: [] })
  })

  it('LTI-001: 取得失敗時に league-transfer-inbound-error-state が描画される', async () => {
    getInboundTransfers.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await openInboundTab(wrapper)

    expect(wrapper.find('[data-testid="league-transfer-inbound-error-state"]').exists()).toBe(true)
  })

  it('LTI-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    getInboundTransfers.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await openInboundTab(wrapper)

    expect(wrapper.find('[data-testid="league-transfer-inbound-error-state"]').exists()).toBe(false)
  })

  it('LTI-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    getInboundTransfers.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(LeagueTransfersPage)
    await flushMicrotasks()
    await openInboundTab(wrapper)
    expect(wrapper.find('[data-testid="league-transfer-inbound-error-state"]').exists()).toBe(true)

    getInboundTransfers.mockResolvedValueOnce({ data: [] })
    const callsBefore = getInboundTransfers.mock.calls.length
    await wrapper.find('[data-testid="league-transfer-inbound-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(getInboundTransfers.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="league-transfer-inbound-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
