import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BillingManagePanel from './BillingManagePanel.vue'

/**
 * 試練D（第5隊）AC-125: 画面の結線を測る（最重要・PR6a と同型の欠陥の再発防止）。
 *
 * PR6a では「新しい解約ダイアログがどの画面からも参照されていない」欠陥が、
 * 単体テスト8件緑・CI 全緑をすり抜けた（`BillingManagePanel.spec.ts` 冒頭コメント参照）。
 * 本ファイルは同型の欠陥（プラン変更ダイアログが画面から到達できない）を、
 * `mountSuspended` ＋ 実クリックで USER/TEAM/ORG の3スコープすべてについて固定する。
 *
 * 現状（PR6b-1 実装前）:
 *   - `BillingManagePanel.vue` にプラン変更ボタン（`data-testid="billing-change-plan"` 想定）が無い
 *   - `BillingPlanChangeDialog.vue` 自体が存在しない
 * したがって本テストは import 解決の失敗または要素不在で red になる（スタブ差し替え禁止）。
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string | null | undefined) => v ?? '',
  formatDateTime: (v: string | null | undefined) => v ?? '',
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

function activePlanFixture(overrides: Record<string, unknown> = {}) {
  return {
    contractId: '00000000-0000-7000-8000-000000000001',
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

beforeEach(() => { mockApi.mockReset() })
afterEach(() => { vi.restoreAllMocks() })

describe.each([
  { scopeKind: 'USER' as const, scopeId: '' },
  { scopeKind: 'TEAM' as const, scopeId: 'team-slug-1' },
  { scopeKind: 'ORG' as const, scopeId: 'org-slug-1' },
])('AC-125: BillingManagePanel($scopeKind) — プラン変更ボタン押下で変更ダイアログが実際に開く', ({ scopeKind, scopeId }) => {
  it(`スタブ置換なしで ${scopeKind} スコープの結線を確認する`, async () => {
    mockApi.mockResolvedValueOnce(entitlementsResponse(activePlanFixture()))

    const wrapper = await mountSuspended(BillingManagePanel, {
      props: { scopeKind, scopeId, canManage: true },
    })
    await flushPromises()

    // 是正前提: 変更ダイアログはまだ開いていない
    expect(wrapper.find('[data-testid="billing-plan-change-dialog"]').exists()).toBe(false)

    const changeButton = wrapper.find('[data-testid="billing-change-plan"]')
    expect(changeButton.exists()).toBe(true)
    await changeButton.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('[data-testid="billing-plan-change-dialog"]')
    expect(dialog.exists()).toBe(true)
  })
})
