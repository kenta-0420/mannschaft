import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BillingManagePanel from './BillingManagePanel.vue'

/**
 * Billing Center PR6b-1 修繕（2巡目） — Codex 再検分の P1・P2-1・P2-3・P2-4 の番人。
 *
 * <p>いずれも「押しても何も起きない」「誤った期限を見せる」「必ず失敗する選択肢を出す」という、
 * <b>全緑をすり抜ける種類</b>の欠陥である。ここでは実マウント＋実クリックで、実 API が期待どおり
 * 呼ばれる／呼ばれないことを直接主張する。</p>
 *
 * <ul>
 *   <li><b>P1</b>(AC-71): ページ再読込・別端末では `planChangeId`（ローカル ref）が null に戻るため、
 *       「支払いを再開する」は早期 return で何もしなかった。BE 投影の `pendingChange.changeId` から
 *       再開できることを主張する。</li>
 *   <li><b>P2-1</b>(AC-105): 期限として `effectiveAt`（変更行を作った時刻）を表示していた。
 *       実際の期限 `pendingUpdateExpiresAt` を出し、取れないときは期限を断定しないこと。</li>
 *   <li><b>P2-3</b>(AC-135): ポーリング中の再取得が失敗すると暫定状態を捨てて監視が止まった。
 *       失敗時は暫定状態を保って再試行すること。</li>
 *   <li><b>P2-4</b>: 変更先候補に必ず 409 になる下位・同額プランが出ていた。</li>
 * </ul>
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
const CHANGE_ID = '00000000-0000-7000-8000-000000000020'
const PREVIEW_ID = '00000000-0000-7000-8000-000000000010'
const CLIENT_SECRET = 'pi_test_secret_value'
const EFFECTIVE_AT = '2026-09-16T00:00:00Z'
const PENDING_UPDATE_EXPIRES_AT = '2026-09-16T23:00:00Z'

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

function entitlementsResponse(activePlan: Record<string, unknown>) {
  return {
    data: {
      scopeKind: 'USER',
      scopeId: 1,
      activePlan,
      activeAddons: [],
      entitledFeatures: [],
    },
  }
}

/** カタログ: FREE(無償) / BASIC(1000・現行) / STANDARD(1000・同額) / FULL(2000・上位)。 */
function catalogResponse() {
  return {
    data: {
      plans: [
        { planKey: 'FREE', displayNameKey: 'billing.plans.free.name', baseMonthlyPriceJpy: null },
        { planKey: 'BASIC', displayNameKey: 'billing.plans.basic.name', baseMonthlyPriceJpy: 1000 },
        { planKey: 'STANDARD', displayNameKey: 'billing.plans.standard.name', baseMonthlyPriceJpy: 1000 },
        { planKey: 'FULL', displayNameKey: 'billing.plans.full.name', baseMonthlyPriceJpy: 2000 },
      ],
    },
  }
}

async function mountPanel() {
  const wrapper = await mountSuspended(BillingManagePanel, {
    props: { scopeKind: 'USER' as const, scopeId: '', canManage: true },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mockApi.mockReset()
  confirmPaymentAction.mockReset()
  confirmPaymentAction.mockResolvedValue({ status: 'succeeded', paymentIntentStatus: 'succeeded' })
})
afterEach(() => { vi.restoreAllMocks() })

describe('P1(AC-71): 再読込・別端末からでも 3DS を再開できる', () => {
  it('BE 投影の changeId から GET …/payment-action が実際に呼ばれる', async () => {
    const pending = {
      changeId: CHANGE_ID,
      status: 'REQUIRES_ACTION',
      effectiveAt: EFFECTIVE_AT,
      paymentActionRequired: true,
      pendingUpdateExpiresAt: PENDING_UPDATE_EXPIRES_AT,
    }
    mockApi.mockImplementation(async (url: string) => {
      if (String(url).includes('/entitlements')) return entitlementsResponse(activePlanFixture({ pendingChange: pending }))
      if (String(url) === '/api/v1/billing/plans') return catalogResponse()
      if (String(url).includes('/payment-action')) {
        return { data: { paymentAction: { type: 'use_stripe_sdk', clientSecret: CLIENT_SECRET, expiresAt: PENDING_UPDATE_EXPIRES_AT } } }
      }
      throw new Error(`unexpected API call: ${String(url)}`)
    })

    const wrapper = await mountPanel()
    // ページ再読込を模す: このセッションでは一度も変更を実行していない（planChangeId は null）。
    await wrapper.get('[data-testid="billing-change-plan"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="plan-change-resume-payment-action-button"]').trigger('click')
    await flushPromises()

    const actionCall = mockApi.mock.calls.find(([url]) => String(url).includes('/payment-action'))
    expect(actionCall).toBeDefined()
    expect(String(actionCall?.[0])).toBe(
      `/api/v1/me/billing/contracts/${CONTRACT_ID}/changes/${CHANGE_ID}/payment-action`,
    )
    expect(confirmPaymentAction).toHaveBeenCalledTimes(1)
    expect(confirmPaymentAction.mock.calls[0]?.[0]).toMatchObject({ clientSecret: CLIENT_SECRET })
  })
})

describe('P2-1(AC-105): 支払期限は pending_update の失効時刻であり effectiveAt ではない', () => {
  it('期限がある場合は pendingUpdateExpiresAt を出し、effectiveAt を期限として出さない', async () => {
    mockApi.mockImplementation(async (url: string) => {
      if (String(url).includes('/entitlements')) {
        return entitlementsResponse(activePlanFixture({
          pendingChange: {
            changeId: CHANGE_ID,
            status: 'REQUIRES_ACTION',
            effectiveAt: EFFECTIVE_AT,
            paymentActionRequired: true,
            pendingUpdateExpiresAt: PENDING_UPDATE_EXPIRES_AT,
          },
        }))
      }
      throw new Error(`unexpected API call: ${String(url)}`)
    })

    const wrapper = await mountPanel()
    const notice = wrapper.get('[data-testid="billing-plan-change-pending-notice"]').text()
    expect(notice).toContain('billing.manage.planChange.expiresAtNotice')
    expect(notice).toContain(PENDING_UPDATE_EXPIRES_AT)
    expect(notice).not.toContain(EFFECTIVE_AT)
  })

  it('期限が取れない場合は嘘の期限を出さない（断定しない文言へ倒す）', async () => {
    mockApi.mockImplementation(async (url: string) => {
      if (String(url).includes('/entitlements')) {
        return entitlementsResponse(activePlanFixture({
          pendingChange: {
            changeId: CHANGE_ID,
            status: 'PENDING_PAYMENT',
            effectiveAt: EFFECTIVE_AT,
            paymentActionRequired: false,
            pendingUpdateExpiresAt: null,
          },
        }))
      }
      throw new Error(`unexpected API call: ${String(url)}`)
    })

    const wrapper = await mountPanel()
    const notice = wrapper.get('[data-testid="billing-plan-change-pending-notice"]').text()
    expect(notice).toContain('billing.manage.planChange.pendingPaymentNotice')
    expect(notice).not.toContain('billing.manage.planChange.expiresAtNotice')
    expect(notice).not.toContain(EFFECTIVE_AT)
  })
})

describe('P2-3(AC-135): 再取得が失敗しても暫定状態を捨てず監視を続ける', () => {
  it('実行直後の最初の再取得が失敗しても、次の周で再取得を試みる', async () => {
    let entitlementsCalls = 0
    mockApi.mockImplementation(async (url: string) => {
      if (String(url).includes('/entitlements')) {
        entitlementsCalls += 1
        // 1回目=初期表示（支払い待ちなし）、2回目=ポーリング初回で一時的な通信エラー、
        // 3回目=復旧して APPLIED（＝pendingChange は消える）。
        if (entitlementsCalls === 2) throw new Error('temporary network failure')
        return entitlementsResponse(activePlanFixture())
      }
      if (String(url) === '/api/v1/billing/plans') return catalogResponse()
      if (String(url).includes('/change-previews')) {
        return {
          data: {
            previewId: PREVIEW_ID,
            kind: 'UPGRADE',
            amountDueNow: {
              currency: 'JPY', amountIncludingTax: 1500, amountExcludingTax: 1364,
              taxAmount: 136, taxName: '消費税', taxRateBasisPoints: 1000,
            },
            effectiveAt: EFFECTIVE_AT,
            expiresAt: '2026-09-16T00:10:00Z',
          },
        }
      }
      if (String(url).endsWith('/changes')) {
        return { data: { changeId: CHANGE_ID, status: 'PENDING_PAYMENT', effectiveAt: EFFECTIVE_AT } }
      }
      throw new Error(`unexpected API call: ${String(url)}`)
    })

    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="billing-change-plan"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="plan-change-target-select"]').setValue('FULL')
    await flushPromises()
    await wrapper.get('[data-testid="plan-change-confirm-button"]').trigger('click')
    await flushPromises()

    // 欠陥版は「失敗した再取得」を成功と見なして 1 周で監視を打ち切る（=2）。
    expect(entitlementsCalls).toBeGreaterThanOrEqual(3)
  })
})

describe('P2-4: 必ず 409 になる変更先を候補に出さない', () => {
  it('現行 BASIC の候補は上位プランだけ（FREE・同額 STANDARD・自分自身は出ない）', async () => {
    mockApi.mockImplementation(async (url: string) => {
      if (String(url).includes('/entitlements')) return entitlementsResponse(activePlanFixture())
      if (String(url) === '/api/v1/billing/plans') return catalogResponse()
      throw new Error(`unexpected API call: ${String(url)}`)
    })

    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="billing-change-plan"]').trigger('click')
    await flushPromises()

    const values = wrapper.get('[data-testid="plan-change-target-select"]')
      .findAll('option').map(o => o.attributes('value'))
    expect(values).toEqual(['', 'FULL'])
  })
})
