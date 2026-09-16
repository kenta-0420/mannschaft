import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 試練D（第5隊）AC-133/134/135: `BillingActiveContract` 投影の `pendingChange` と、
 * FE ポーリングの間隔・回数上限を固定する。
 *
 * PR6b-1 実装前は `useBillingApi.ts` に upgrade 系のエンドポイント呼び出しが
 * 一切無いため、本ファイルは import した関数が undefined であることをもって red になる。
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

beforeEach(() => { mockApi.mockReset() })
afterEach(() => { vi.restoreAllMocks() })

describe('AC-133: BillingActiveContract 投影に pendingChange が載る（USER/TEAM/ORG）', () => {
  it.each([
    { scopeKind: 'USER' as const, scopeId: '' },
    { scopeKind: 'TEAM' as const, scopeId: 'team-1' },
    { scopeKind: 'ORG' as const, scopeId: 'org-1' },
  ])('$scopeKind スコープで pendingChange(status/effectiveAt/paymentActionRequired) を透過する', async ({ scopeKind, scopeId }) => {
    const { useBillingApi } = await import('./useBillingApi')
    const billingApi = useBillingApi()

    mockApi.mockResolvedValueOnce({
      data: {
        scopeKind,
        scopeId: 1,
        activePlan: {
          contractId: '00000000-0000-7000-8000-000000000001',
          planKey: 'BASIC',
          status: 'ACTIVE',
          pendingChange: {
            status: 'REQUIRES_ACTION',
            effectiveAt: '2026-09-16T00:00:00Z',
            paymentActionRequired: true,
          },
        },
        activeAddons: [],
        entitledFeatures: [],
      },
    })

    const res = await billingApi.getEntitlements(scopeKind, scopeId)
    expect(res.data.activePlan?.pendingChange).toEqual({
      status: 'REQUIRES_ACTION',
      effectiveAt: '2026-09-16T00:00:00Z',
      paymentActionRequired: true,
    })
  })
})

describe('AC-134: pendingChange 投影が N+1 にならない（FE 側は単一 API 呼び出しで完結することを固定）', () => {
  it('契約 N件でも getEntitlements の呼び出しは1回（BE 側の SQL 本数は BE IT が別途測る）', async () => {
    const { useBillingApi } = await import('./useBillingApi')
    const billingApi = useBillingApi()

    mockApi.mockResolvedValueOnce({
      data: {
        scopeKind: 'ORG',
        scopeId: 1,
        activePlan: { contractId: 'c1', planKey: 'BASIC', status: 'ACTIVE', pendingChange: null },
        activeAddons: Array.from({ length: 20 }, (_, i) => ({
          contractId: `addon-${i}`,
          featureKey: `FEATURE_${i}`,
          status: 'ACTIVE',
          pendingChange: null,
        })),
        entitledFeatures: [],
      },
    })

    await billingApi.getEntitlements('ORG', 'org-1')
    // FE は1回の GET で全件（activePlan + N件の activeAddons の pendingChange 込み）を受け取る。
    // 契約件数ぶん追加リクエストが飛んでいたら N+1 の兆候であり、この期待値が壊れる。
    expect(mockApi.mock.calls.length).toBe(1)
  })
})

describe('AC-135: FE のポーリングに間隔と回数の上限がある（payment-action は Stripe を都度叩くため）', () => {
  it('usePlanChangePolling が既定の間隔・最大試行回数を公開する', async () => {
    // 実装が無い間は import 自体が失敗して red になる。
    const mod = await import('./usePlanChangePolling')
    expect(typeof mod.usePlanChangePolling).toBe('function')

    const polling = mod.usePlanChangePolling()
    expect(polling.intervalMs).toBeGreaterThan(0)
    expect(polling.maxAttempts).toBeGreaterThan(0)
    expect(Number.isFinite(polling.maxAttempts)).toBe(true)
  })

  it('最大試行回数に到達するとポーリングを打ち切る（無限ポーリング防止）', async () => {
    const mod = await import('./usePlanChangePolling')
    const poll = vi.fn().mockResolvedValue({ done: false })
    const polling = mod.usePlanChangePolling({ intervalMs: 0, maxAttempts: 3 })

    await polling.start(poll)

    expect(poll.mock.calls.length).toBeLessThanOrEqual(3)
    expect(poll.mock.calls.length).toBeGreaterThan(0)
  })
})
