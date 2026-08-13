import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RecruitmentCancellationFeeWaiveModal from '~/components/recruitment/RecruitmentCancellationFeeWaiveModal.vue'
import type { RecruitmentCancellationRecordSummary } from '~/types/recruitment'

/**
 * F03.11.1 キャンセル料免除 確認モーダルのユニットテスト（設計書 §12.1）。
 *
 * <p>文言そのもの（言い切り禁止・否定形・順序）の固定は
 * {@code tests/unit/locales/cancellationFeeWaiveMessage.spec.ts} が担う
 * （ロケールファイルをソースとして直接検証するため、表示ロケールに依存しない）。
 * 本テストは構造的な振る舞いのみを見る——{@code data-testid} で要素を探すことで、
 * テスト実行環境の既定ロケールに関わらず安定させる。</p>
 *
 * 観点:
 *  - 免除額を差し込んだ確認文言が実際に描画される（金額の埋め込みのみを見る）
 *  - 免除理由が空のままでは確定ボタンが disabled
 *  - 理由を入力すると確定ボタンが enable になり、確定で confirm イベントが発火する
 *  - キャンセルボタンで update:visible(false) が emit される
 */

function buildRecord(overrides: Partial<RecruitmentCancellationRecordSummary> = {}): RecruitmentCancellationRecordSummary {
  return {
    id: 1,
    listingId: 10,
    listingTitle: 'テスト募集',
    participantId: 100,
    userId: 200,
    feeAmount: 3000,
    paymentStatus: 'PENDING',
    cancelledAt: '2026-08-01T10:00:00',
    hoursBeforeStart: 6,
    ...overrides,
  }
}

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('RecruitmentCancellationFeeWaiveModal.vue', () => {
  it('確認文言に免除額が差し込まれる', async () => {
    await mountSuspended(RecruitmentCancellationFeeWaiveModal, {
      props: { visible: true, record: buildRecord({ feeAmount: 3000 }) },
    })

    const messageEl = findByTestId('waive-confirm-message')
    expect(messageEl).not.toBeNull()
    expect(messageEl!.textContent).toContain('3,000')
  })

  it('免除理由が空のままでは確定ボタンが disabled', async () => {
    await mountSuspended(RecruitmentCancellationFeeWaiveModal, {
      props: { visible: true, record: buildRecord() },
    })

    const confirmBtn = findByTestId<HTMLButtonElement>('waive-confirm-button')
    expect(confirmBtn).not.toBeNull()
    expect(confirmBtn!.disabled).toBe(true)
    expect(findByTestId('waive-reason-error')).not.toBeNull()
  })

  it('理由を入力すると確定ボタンが enable になり、確定で confirm(reason) が emit される', async () => {
    const wrapper = await mountSuspended(RecruitmentCancellationFeeWaiveModal, {
      props: { visible: true, record: buildRecord() },
    })

    const textarea = findByTestId<HTMLTextAreaElement>('waive-reason-input')
    expect(textarea).not.toBeNull()
    textarea!.value = '主催者都合のため'
    textarea!.dispatchEvent(new Event('input'))
    await wrapper.vm.$nextTick()

    const confirmBtn = findByTestId<HTMLButtonElement>('waive-confirm-button')
    expect(confirmBtn!.disabled).toBe(false)

    confirmBtn!.click()
    await wrapper.vm.$nextTick()

    const emitted = wrapper.emitted('confirm')
    expect(emitted).toBeTruthy()
    expect(emitted?.[0]?.[0]).toBe('主催者都合のため')
  })

  it('キャンセルボタンで update:visible(false) が emit される', async () => {
    const wrapper = await mountSuspended(RecruitmentCancellationFeeWaiveModal, {
      props: { visible: true, record: buildRecord() },
    })

    const cancelBtn = findByTestId<HTMLButtonElement>('waive-cancel-button')
    expect(cancelBtn).not.toBeNull()
    cancelBtn!.click()
    await wrapper.vm.$nextTick()

    const closeEmits = wrapper.emitted('update:visible')
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })
})
