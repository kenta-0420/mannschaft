import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BillingManagePanel from './BillingManagePanel.vue'

/**
 * Billing Center PR6b-1 H群の**番人** — 「確定ボタンを押したら実 API が呼ばれる」ことを直接主張する。
 *
 * <p><b>この番人を置いた理由</b>: PR6a では「新しいダイアログがどこからも参照されていない」欠陥が
 * 単体テスト緑・CI 全緑をすり抜けた。PR6b-1 では同じ事故が**二度目**として起きた——
 * `BillingManagePanel.vue` は `BillingPlanChangeDialog` へ `:preview="null"` `:target-plan-key="''"`
 * `:submitting="false"` を固定値で渡し、`@confirm` に相当するハンドラを一つも繋いでいなかった。
 * `BillingManagePanel.planChange.spec.ts`（AC-125）は「ダイアログが開くこと」しか測っておらず、
 * **押しても何も起きないこと**を見逃した。</p>
 *
 * <p>したがって本ファイルは「開くこと」では満足せず、
 * <b>見積り `POST …/change-previews` → 適用 `POST …/changes` → 3DS `GET …/payment-action`</b>
 * が**期待の body で実際に呼ばれた**ことを `useApi` の spy で assert する。
 * スタブでダイアログを置換しない（`mountSuspended` の実マウント＋実クリック）。</p>
 *
 * <p><b>結線前に red であることの実測</b>: 本ファイルは結線前のコード（`@confirm` 未接続・
 * `useBillingApi` に upgrade 系の呼び出しが 0 件）では、変更先プランの選択要素が存在せず
 * `wrapper.get('[data-testid="plan-change-target-select"]')` で必ず失敗する。</p>
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

const confirmPaymentAction = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string | null | undefined) => v ?? '',
  formatDateTime: (v: string | null | undefined) => v ?? '',
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }))
mockNuxtImport('useStripeSetup', () => () => ({ confirmPaymentAction }))
// ポーリング間隔をゼロにして実時計待ちを排除する（AC-135 の既定値そのものは
// `usePlanChangePolling.planChange.spec.ts` が別途固定している）。
mockNuxtImport('usePlanChangePolling', () => () => ({
  intervalMs: 0,
  maxAttempts: 3,
  start: async (poll: () => Promise<{ done: boolean }>) => {
    for (let i = 0; i < 3; i++) {
      const r = await poll()
      if (r.done) return
    }
  },
}))

const CONTRACT_ID = '00000000-0000-7000-8000-000000000001'
const PREVIEW_ID = '00000000-0000-7000-8000-000000000010'
const CHANGE_ID = '00000000-0000-7000-8000-000000000020'
const CLIENT_SECRET = 'pi_test_secret_value'

function activePlanFixture(overrides: Record<string, unknown> = {}) {
  return {
    contractId: CONTRACT_ID,
    planKey: 'BASIC',
    featureKey: null,
    contractedAt: '2026-01-01T00:00:00Z',
    priceJpySnapshot: 1000,
    status: 'ACTIVE',
    currentPeriodEnd: '2026-09-30T15:00:00Z',
    canCancel: true,
    canResume: false,
    cancel: null,
    version: 3,
    pendingChange: null,
    ...overrides,
  }
}

function previewResponse() {
  return {
    data: {
      previewId: PREVIEW_ID,
      kind: 'UPGRADE',
      amountDueNow: {
        currency: 'JPY',
        amountIncludingTax: 1500,
        amountExcludingTax: 1364,
        taxAmount: 136,
        taxName: '消費税',
        taxRateBasisPoints: 1000,
      },
      effectiveAt: '2026-09-16T00:00:00Z',
      expiresAt: '2026-09-16T00:10:00Z',
    },
  }
}

/** URL でルーティングする API スタブ。想定外の URL は握りつぶさず明示的に失敗させる。 */
function installApiRouter(changeStatus: 'APPLIED' | 'REQUIRES_ACTION') {
  mockApi.mockImplementation(async (url: string) => {
    if (String(url).includes('/entitlements')) {
      return {
        data: {
          scopeKind: 'USER',
          scopeId: 1,
          activePlan: activePlanFixture(),
          activeAddons: [],
          entitledFeatures: [],
        },
      }
    }
    if (String(url) === '/api/v1/billing/plans') {
      return {
        data: {
          plans: [
            { planKey: 'BASIC', displayNameKey: 'billing.plans.basic.name', baseMonthlyPriceJpy: 1000 },
            { planKey: 'FULL', displayNameKey: 'billing.plans.full.name', baseMonthlyPriceJpy: 2000 },
          ],
        },
      }
    }
    if (String(url).includes('/change-previews')) return previewResponse()
    if (String(url).includes('/payment-action')) {
      return { data: { paymentAction: { type: 'use_stripe_sdk', clientSecret: CLIENT_SECRET, expiresAt: '2026-09-16T00:15:00Z' } } }
    }
    if (String(url).endsWith('/changes')) {
      return { data: { changeId: CHANGE_ID, status: changeStatus, effectiveAt: '2026-09-16T00:00:00Z' } }
    }
    throw new Error(`unexpected API call: ${String(url)}`)
  })
}

async function openDialogAndSelectTarget(scopeKind: 'USER' | 'TEAM' | 'ORG', scopeId: string) {
  const wrapper = await mountSuspended(BillingManagePanel, {
    props: { scopeKind, scopeId, canManage: true },
  })
  await flushPromises()

  await wrapper.get('[data-testid="billing-change-plan"]').trigger('click')
  await flushPromises()

  const select = wrapper.get('[data-testid="plan-change-target-select"]')
  await select.setValue('FULL')
  await flushPromises()

  return wrapper
}

beforeEach(() => {
  mockApi.mockReset()
  confirmPaymentAction.mockReset()
  confirmPaymentAction.mockResolvedValue({ status: 'succeeded', paymentIntentStatus: 'succeeded' })
})
afterEach(() => { vi.restoreAllMocks() })

describe.each([
  { scopeKind: 'USER' as const, scopeId: '' },
  { scopeKind: 'TEAM' as const, scopeId: 'team-slug-1' },
  { scopeKind: 'ORG' as const, scopeId: 'org-slug-1' },
])('番人($scopeKind): 確定ボタンで実 API が呼ばれる（no-op でないこと）', ({ scopeKind, scopeId }) => {
  it('変更先の選択で change-previews が、確定で changes が、期待の body で呼ばれる', async () => {
    installApiRouter('APPLIED')
    const wrapper = await openDialogAndSelectTarget(scopeKind, scopeId)

    // 見積り（AC-1〜9）: 選択の時点で Stripe 由来の金額を取りに行く
    const previewCall = mockApi.mock.calls.find(([url]) => String(url).includes('/change-previews'))
    expect(previewCall).toBeDefined()
    expect(String(previewCall?.[0])).toBe(`/api/v1/me/billing/contracts/${CONTRACT_ID}/change-previews`)
    expect(previewCall?.[1]).toMatchObject({
      method: 'POST',
      body: { toProductKind: 'PLAN', toProductKey: 'FULL', version: 3 },
    })

    // 見積りの金額が画面に出ている（押す前に見せる・AC-126）
    expect(wrapper.get('[data-testid="plan-change-amount-due-now"]').text()).toContain('1500')

    // 確定（AC-29〜31）: ここが no-op だった欠陥の本体
    await wrapper.get('[data-testid="plan-change-confirm-button"]').trigger('click')
    await flushPromises()

    const changeCall = mockApi.mock.calls.find(([url]) => String(url).endsWith('/changes'))
    expect(changeCall).toBeDefined()
    expect(String(changeCall?.[0])).toBe(`/api/v1/me/billing/contracts/${CONTRACT_ID}/changes`)
    expect(changeCall?.[1]).toMatchObject({
      method: 'POST',
      body: { previewId: PREVIEW_ID, version: 3 },
    })
  })
})

describe('番人: REQUIRES_ACTION では payment-action を取得して 3DS を発火する', () => {
  it('GET …/payment-action が呼ばれ、clientSecret が confirmPaymentAction へ渡る', async () => {
    installApiRouter('REQUIRES_ACTION')
    const wrapper = await openDialogAndSelectTarget('USER', '')

    await wrapper.get('[data-testid="plan-change-confirm-button"]').trigger('click')
    await flushPromises()

    const actionCall = mockApi.mock.calls.find(([url]) => String(url).includes('/payment-action'))
    expect(actionCall).toBeDefined()
    expect(String(actionCall?.[0])).toBe(
      `/api/v1/me/billing/contracts/${CONTRACT_ID}/changes/${CHANGE_ID}/payment-action`,
    )
    expect(confirmPaymentAction).toHaveBeenCalledTimes(1)
    expect(confirmPaymentAction.mock.calls[0]?.[0]).toMatchObject({ clientSecret: CLIENT_SECRET })
  })

  it('AC-55/57/59: clientSecret が DOM・URL・browser storage のいずれにも残らない', async () => {
    installApiRouter('REQUIRES_ACTION')
    const wrapper = await openDialogAndSelectTarget('USER', '')

    await wrapper.get('[data-testid="plan-change-confirm-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.html()).not.toContain(CLIENT_SECRET)
    expect(window.location.href).not.toContain(CLIENT_SECRET)
    expect(JSON.stringify({ ...localStorage })).not.toContain(CLIENT_SECRET)
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(CLIENT_SECRET)
  })
})
