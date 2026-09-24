import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 試練D（第5隊）AC-133/134: `BillingActiveContract` 投影の `pendingChange` を固定する。
 *
 * PR6b-1 実装前は `useBillingApi.ts` に upgrade 系のエンドポイント呼び出しが
 * 一切無いため、本ファイルは import した関数が undefined であることをもって red になる。
 *
 * AC-135（`usePlanChangePolling`）は隣接ファイル `usePlanChangePolling.planChange.spec.ts` へ
 * 分離してある。存在しないモジュールへの動的 import は Vite の変換段階で解決に失敗し、
 * 同一ファイル内の無関係なテストまで巻き添えで「収集失敗（0件実行）」になることを
 * 前回の実測（試練D・再開時）で確認したため、AC-133/134 の red 判定を汚染しないよう分離した。
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
