import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BillingPlanChangeDialog from './BillingPlanChangeDialog.vue'

/**
 * Billing Center PR6b-1 修繕（2巡目） — Codex 再検分 <b>P2-2</b> の番人。
 *
 * <p>見積り（`preview`）の有無を見る共通フラグ `confirmDisabled` が、閉じるボタンと
 * 支払い再開ボタンにも掛かっていたため、`preview` が null の状態（ダイアログを開いた直後・
 * 既存の支払い待ちの表示）では<b>利用者がダイアログを閉じられず、3DS も再開できなかった</b>。
 * 見積り必須の判定は確定ボタンだけに掛かること。</p>
 */

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string | null | undefined) => v ?? '',
  formatDateTime: (v: string | null | undefined) => v ?? '',
}))

const CHANGE_ID = '00000000-0000-7000-8000-000000000020'

beforeEach(() => { vi.restoreAllMocks() })
afterEach(() => { vi.restoreAllMocks() })

async function mountPending(onResumePaymentAction: () => Promise<void>) {
  return mountSuspended(BillingPlanChangeDialog, {
    props: {
      open: true,
      preview: null,
      currentPlanKey: 'BASIC',
      targetPlanKey: '',
      plans: [],
      pendingChange: {
        changeId: CHANGE_ID,
        status: 'REQUIRES_ACTION',
        effectiveAt: '2026-09-16T00:00:00Z',
        paymentActionRequired: true,
        pendingUpdateExpiresAt: '2026-09-16T23:00:00Z',
      },
      changeError: null,
      submitting: false,
      refetching: false,
      onResumePaymentAction,
    },
  })
}

describe('P2-2: 見積りが無くても閉じられる・3DS を再開できる', () => {
  it('preview が null でも閉じるボタンは押せて cancel を emit する', async () => {
    const wrapper = await mountPending(async () => {})
    const dismiss = wrapper.get('[data-testid="plan-change-dismiss-button"]')
    expect(dismiss.attributes('disabled')).toBeUndefined()

    await dismiss.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('cancel')).toBeTruthy()
    expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
  })

  it('preview が null でも支払い再開ボタンが押せてハンドラが呼ばれる', async () => {
    const onResume = vi.fn(async () => {})
    const wrapper = await mountPending(onResume)
    const resume = wrapper.get('[data-testid="plan-change-resume-payment-action-button"]')
    expect(resume.attributes('disabled')).toBeUndefined()

    await resume.trigger('click')
    await flushPromises()
    expect(onResume).toHaveBeenCalledTimes(1)
  })

  it('回帰: 確定ボタンは preview が無い間は押せないまま', async () => {
    const wrapper = await mountPending(async () => {})
    expect(wrapper.get('[data-testid="plan-change-confirm-button"]').attributes('disabled')).toBeDefined()
  })
})
