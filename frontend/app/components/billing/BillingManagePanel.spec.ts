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

  it('安全網: BE投影に version が欠落している間は 0 決め打ちで送らず、確定操作を誠実に失敗させる（対処療法禁止）', async () => {
    // 通常運用では BE（ActiveContract）が version を返す（第7隊 8f0a0bb5a1）。
    // ここでは欠落時のフォールバック（誠実な失敗）を固定する安全網テストとして、
    // わざと version を持たないフィクスチャを使う。
    mockApi.mockResolvedValueOnce(entitlementsResponse(activePlanFixture({ version: undefined })))

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

  it('検分P2: 権利再取得（onRefetch）が完了するまで確定ボタンはdisabledのまま', async () => {
    // 1回目: 初期表示の権利取得
    mockApi.mockResolvedValueOnce(entitlementsResponse(activePlanFixture({ version: 0 })))
    // 2回目: 解約確定（cancelContractReservation の POST）— すぐ成功する
    mockApi.mockResolvedValueOnce({
      data: {
        contractId: '00000000-0000-7000-8000-000000000001',
        contractStatus: 'ACTIVE',
        status: 'SCHEDULED',
        scheduledAt: '2026-09-15T00:00:00Z',
        endAt: '2026-09-30T15:00:00Z',
        currentPeriodEnd: '2026-09-30T15:00:00Z',
        version: 1,
        canCancel: false,
        canResume: true,
      },
    })
    // 3回目: onConfirm 成功後に onRefetch が呼ぶ権利再取得（getEntitlements）— わざと遅延させる
    let resolveRefetch: (() => void) | undefined
    const refetchPending = new Promise((resolve) => {
      resolveRefetch = () => resolve(entitlementsResponse(activePlanFixture({ version: 1, canCancel: false, canResume: true })))
    })
    mockApi.mockImplementationOnce(() => refetchPending)

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind: 'USER', scopeId: '', canManage: true },
    })
    await flushPromises()
    await wrapper.get('[data-testid="billing-cancel-plan"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="cancel-confirm-button"]').trigger('click')
    await flushPromises()

    // cancel API 自体はもう成功している（3回目の呼び出し＝再取得が発生済み）が、
    // その再取得がまだ pending の間はボタンが disabled のままでなければならない
    // （検分 P2: 再取得完了を待たずに解除すると、古い canCancel/version のまま連打でき、
    // 別の Idempotency-Key で重複要求を送って 409 の誤ったエラー表示を招く）。
    expect(mockApi.mock.calls.length).toBe(3)
    const confirmButton = wrapper.get('[data-testid="cancel-confirm-button"]').element as HTMLButtonElement
    expect(confirmButton.disabled).toBe(true)

    // 連打しても cancel API は再度呼ばれない
    await wrapper.get('[data-testid="cancel-confirm-button"]').trigger('click')
    await flushPromises()
    expect(mockApi.mock.calls.length).toBe(3)

    resolveRefetch?.()
    await flushPromises()
    await flushPromises()

    // 再取得完了後は disabled が解除され、更新後の投影（撤回ボタン）が反映されている
    expect(wrapper.find('[data-testid="resume-cancel-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="cancel-confirm-button"]').exists()).toBe(false)
  })
})
