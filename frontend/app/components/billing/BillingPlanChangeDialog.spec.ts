import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * 試練D（第5隊）AC-126〜AC-132: プラン変更ダイアログ（`BillingPlanChangeDialog`）の表示の誠実さ。
 *
 * PR6b-1 時点ではこのコンポーネントは存在しない（Billing Center PR6b-1 の H群は未実装）。
 * `mountSuspended` で実マウントし、スタブに置き換えないことで
 * 「実装が無いから何も起きず通る」空虚な緑を避ける。
 *
 * 契約: `BillingPlanChangeDialog` は以下の props を受け取る想定（第12隊への発注）:
 *   - open: boolean
 *   - preview: { previewId, amountDueNow, taxSnapshot:{ amountInclTax, amountExclTax, taxAmount, taxRate }, effectiveAt, expiresAt } | null
 *   - currentPlanKey: string
 *   - targetPlanKey: string
 *   - pendingChange: { status, effectiveAt, paymentActionRequired } | null
 *   - changeError: string | null （失敗時に「旧プランのままです」を出す判断材料）
 *   - submitting: boolean （進行中はボタン disabled）
 *   - onConfirm / onResumePaymentAction 等のハンドラ
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

async function loadDialog() {
  // 実装が無い間はここで import が解決できず red になる。
  const mod = await import('./BillingPlanChangeDialog.vue')
  return mod.default
}

function previewFixture(overrides: Record<string, unknown> = {}) {
  return {
    previewId: '00000000-0000-7000-8000-000000000010',
    kind: 'UPGRADE',
    amountDueNow: 1500,
    taxSnapshot: {
      amountInclTax: 1500,
      amountExclTax: 1364,
      taxAmount: 136,
      taxRate: 0.10,
    },
    effectiveAt: '2026-09-16T00:00:00Z',
    expiresAt: '2026-09-16T00:10:00Z',
    ...overrides,
  }
}

beforeEach(() => { mockApi.mockReset() })
afterEach(() => { vi.restoreAllMocks() })

describe('BillingPlanChangeDialog — 表示の誠実さ（AC-126〜132）', () => {
  it('AC-126: 変更前に amountDueNow と effectiveAt を必ず見せる', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: previewFixture(),
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: null,
        changeError: null,
        submitting: false,
      },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="plan-change-amount-due-now"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="plan-change-effective-at"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('1500')
  })

  it('AC-127: 税込を主表示し、税抜・税額・税率を preview.taxSnapshot から併記する', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: previewFixture(),
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: null,
        changeError: null,
        submitting: false,
      },
    })
    await flushPromises()

    const inclTax = wrapper.get('[data-testid="plan-change-amount-incl-tax"]')
    const exclTax = wrapper.get('[data-testid="plan-change-amount-excl-tax"]')
    const taxAmount = wrapper.get('[data-testid="plan-change-tax-amount"]')
    const taxRate = wrapper.get('[data-testid="plan-change-tax-rate"]')
    expect(inclTax.text()).toContain('1500')
    expect(exclTax.text()).toContain('1364')
    expect(taxAmount.text()).toContain('136')
    // 税込が主表示であること（DOM順で税込ブロックが税抜より先に出る）
    const html = wrapper.html()
    expect(html.indexOf('plan-change-amount-incl-tax')).toBeLessThan(html.indexOf('plan-change-amount-excl-tax'))
    expect(taxRate.exists()).toBe(true)
  })

  it('AC-128（肯定形）: PENDING_PAYMENT の間はプラン名表示が旧プラン(currentPlanKey)のまま', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: null,
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: { status: 'PENDING_PAYMENT', effectiveAt: '2026-09-16T00:00:00Z', paymentActionRequired: false },
        changeError: null,
        submitting: false,
      },
    })
    await flushPromises()

    const displayedPlan = wrapper.get('[data-testid="plan-change-current-plan-label"]')
    expect(displayedPlan.text()).toContain('BASIC')
    expect(displayedPlan.text()).not.toContain('FULL')
  })

  it('AC-128 対（陽性対照）: pendingChange が無い通常時はプラン名表示にこのフィールドが影響しない（否定形の裏取り）', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: previewFixture(),
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: null,
        changeError: null,
        submitting: false,
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="plan-change-pending-payment-banner"]').exists()).toBe(false)
  })

  it('AC-129: upgrade 失敗時は「旧プランのままです」を明示する', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: previewFixture(),
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: null,
        changeError: 'PLAN_CHANGE_FAILED',
        submitting: false,
      },
    })
    await flushPromises()

    const notice = wrapper.get('[data-testid="plan-change-failed-notice"]')
    expect(notice.text()).toBe('billing.manage.planChange.staysOnOldPlan')
  })

  it('AC-130: 進行中はボタンが disabled で、失敗後は再取得完了を待つまで解除しない', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: previewFixture(),
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: null,
        changeError: null,
        submitting: true,
      },
    })
    await flushPromises()

    const confirmBtn = wrapper.get('[data-testid="plan-change-confirm-button"]').element as HTMLButtonElement
    expect(confirmBtn.disabled).toBe(true)

    // 失敗直後（refetching=true 相当）もまだ disabled のまま
    await wrapper.setProps({ submitting: false, changeError: 'PLAN_CHANGE_FAILED', refetching: true } as never)
    await flushPromises()
    const confirmBtnAfterFail = wrapper.get('[data-testid="plan-change-confirm-button"]').element as HTMLButtonElement
    expect(confirmBtnAfterFail.disabled).toBe(true)
  })

  it('AC-132: 3DS の戻り後、data-testid="plan-change-status" へ focus が移る', async () => {
    const Dialog = await loadDialog()
    const wrapper = await mountSuspended(Dialog, {
      props: {
        open: true,
        preview: null,
        currentPlanKey: 'BASIC',
        targetPlanKey: 'FULL',
        pendingChange: { status: 'REQUIRES_ACTION', effectiveAt: '2026-09-16T00:00:00Z', paymentActionRequired: true },
        changeError: null,
        submitting: false,
        justReturnedFrom3ds: true,
      } as never,
    })
    await flushPromises()

    const statusEl = wrapper.get('[data-testid="plan-change-status"]').element as HTMLElement
    expect(document.activeElement).toBe(statusEl)
  })
})
