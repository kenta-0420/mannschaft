import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BillingManagePanel from './BillingManagePanel.vue'

/**
 * BillingManagePanel ↔ BillingCancelReservationDialog の結線テスト
 * （Codex 検分 P1 是正: 単体テストが緑でも画面に置かれていなければ利用者に届かない、の再発防止）。
 *
 * <p>`useBillingApi` の呼び出し組立て自体は `useBillingApi.spec.ts` で検証済みのため、ここでは
 * 「解約ボタン押下で実際にダイアログが開く」「AC-59（撤回導線は月末前だけ）どおりに
 * ボタンが出し分けられる」「新 API を呼ぶ経路に実際に到達する」ことを検証する。</p>
 *
 * <p><b>version 欠落時の誠実な失敗</b>: 現状の BE 投影（`BillingActiveContract`）には
 * `05_billing_center.md:344` が定める `version` フィールドが無い（backend 側の残課題として
 * 別途報告）。本パネルはこれを 0 決め打ちで埋め合わせず、確定操作を明示的に失敗させて
 * ダイアログの既存エラー表示（AC-62 で試練済み）へ委ねる。これを固定するテストも含む。</p>
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const notificationSuccess = vi.fn()
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string | null | undefined) => v ?? '',
  formatDateTime: (v: string | null | undefined) => v ?? '',
}))
mockNuxtImport('useNotification', () => () => ({ success: notificationSuccess, error: vi.fn() }))

function activePlanFixture(overrides: Record<string, unknown> = {}) {
  return {
    contractId: '00000000-0000-7000-8000-000000000001',
    planKey: 'FULL',
    featureKey: null,
    contractedAt: '2026-01-01T00:00:00Z',
    priceJpySnapshot: 1000,
    status: 'ACTIVE',
    currentPeriodEnd: '2026-09-30T15:00:00Z',
    canCancel: true,
    canResume: false,
    cancel: null,
    ...overrides,
  }
}

function entitlementsResponse(activePlan: unknown) {
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

beforeEach(() => {
  mockApi.mockReset()
  notificationSuccess.mockReset()
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('BillingManagePanel — 新解約ダイアログへの導線（Codex 検分 P1）', () => {
  it('解約ボタン押下で BillingCancelReservationDialog が実際に開く（画面への結線）', async () => {
    mockApi.mockResolvedValueOnce(entitlementsResponse(activePlanFixture()))

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind: 'USER', scopeId: '', canManage: true },
    })
    await flushPromises()

    // 是正前: BillingCancelReservationDialog はどこからも参照されておらず、
    // 画面上に到達する経路が無かった（Codex P1）。是正後はボタン押下で実際に開く。
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.get('[data-testid="billing-cancel-plan"]').trigger('click')
    await flushPromises()

    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(wrapper.find('[data-testid="cancel-confirm-button"]').exists()).toBe(true)
  })

  it('AC-59: canResume=true のときは撤回ボタンだけが表示され、確定ボタンは表示されない', async () => {
    mockApi.mockResolvedValueOnce(
      entitlementsResponse(activePlanFixture({
        canCancel: false,
        canResume: true,
        cancel: { scheduledAt: '2026-09-01T00:00:00Z', endAt: '2026-09-30T15:00:00Z' },
      })),
    )

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind: 'USER', scopeId: '', canManage: true },
    })
    await flushPromises()
    await wrapper.get('[data-testid="billing-cancel-plan"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="resume-cancel-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="cancel-confirm-button"]').exists()).toBe(false)
  })

  it('期末を跨いだ後（canResume=false）は撤回ボタンが表示されない', async () => {
    mockApi.mockResolvedValueOnce(
      entitlementsResponse(activePlanFixture({ canCancel: false, canResume: false, cancel: null })),
    )

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind: 'USER', scopeId: '', canManage: true },
    })
    await flushPromises()
    await wrapper.get('[data-testid="billing-cancel-plan"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="resume-cancel-button"]').exists()).toBe(false)
  })

  it('BE投影に version が無い間は 0 決め打ちで送らず、確定操作を誠実に失敗させる（対処療法禁止）', async () => {
    mockApi.mockResolvedValueOnce(entitlementsResponse(activePlanFixture()))

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind: 'USER', scopeId: '', canManage: true },
    })
    await flushPromises()
    await wrapper.get('[data-testid="billing-cancel-plan"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="cancel-confirm-button"]').trigger('click')
    await flushPromises()

    // 新 cancel エンドポイントへは到達しない（version が無いまま送るのは CAS の意味を失わせる対処療法のため）
    const cancelCall = mockApi.mock.calls.find(([url]) => String(url).includes('/cancel'))
    expect(cancelCall).toBeUndefined()
    expect(wrapper.find('[data-testid="cancel-error"]').exists()).toBe(true)
  })
})
