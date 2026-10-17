import { beforeEach, describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
import ShiftChangeRequestList from './ShiftChangeRequestList.vue'
import type { ChangeRequest } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: 変更依頼一覧の AC。
 *   AC-A3 却下ダイアログの審査コメントが review イベントで渡る
 *   AC-B4 本人の依頼に審査ボタンを出さない / currentUser 未解決なら審査・取り下げを出さない
 *   AC-C1 表ヘッダーに common. の生キーが出ない
 *   AC-I1 取り下げの処理中は disabled
 *   AC-I2 確定ボタンを素早く2回押しても review は1回だけ
 *   AC-M3 依頼者列に名前、null は「不明なユーザー」、userId は出さない
 */
function row(over: {
  id: number
  requestedBy: number
  requesterName?: string | null
  status?: ChangeRequest['reviewInfo']['status']
}): ChangeRequest {
  return {
    id: over.id,
    scheduleId: 10,
    slotId: null,
    requestInfo: { requestType: 'PRE_CONFIRM_EDIT', reason: '理由', requestedBy: over.requestedBy },
    reviewInfo: { status: over.status ?? 'OPEN', reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { expiresAt: null, createdAt: '2026-10-01T00:00:00' },
    requesterName: over.requesterName,
  }
}

const OTHERS = row({ id: 1, requestedBy: 7, requesterName: '山田花子' })
const OWN = row({ id: 2, requestedBy: 5, requesterName: '自分' })

function btn(wrapper: Awaited<ReturnType<typeof mountSuspended>>, id: string) {
  return wrapper.find(`[data-testid="${id}"]`)
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('ShiftChangeRequestList 審査ボタン（AC-B4）', () => {
  it('AC-B4: 他人の OPEN 依頼には審査ボタンが出て、本人の依頼には出ない（取り下げは本人にだけ出る）', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS, OWN], currentUserId: 5, canReview: true },
    })
    expect(btn(wrapper, 'change-request-approve-1').exists()).toBe(true)
    expect(btn(wrapper, 'change-request-reject-1').exists()).toBe(true)
    expect(btn(wrapper, 'change-request-withdraw-1').exists()).toBe(false)

    expect(btn(wrapper, 'change-request-approve-2').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-reject-2').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-withdraw-2').exists()).toBe(true)
  })

  it('AC-B4: currentUserId が未解決（null）なら審査・取り下げボタンをどちらも出さない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS, OWN], currentUserId: null, canReview: true },
    })
    for (const id of [1, 2]) {
      expect(btn(wrapper, `change-request-approve-${id}`).exists()).toBe(false)
      expect(btn(wrapper, `change-request-reject-${id}`).exists()).toBe(false)
      expect(btn(wrapper, `change-request-withdraw-${id}`).exists()).toBe(false)
    }
  })

  it('AC-A2（部品側）: canReview が false なら他人の依頼にも審査ボタンを出さない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: false },
    })
    expect(btn(wrapper, 'change-request-approve-1').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-reject-1').exists()).toBe(false)
  })
})

describe('ShiftChangeRequestList 審査ダイアログ（AC-A3 / AC-I2）', () => {
  it('AC-A3: 却下ダイアログに入力した審査コメントが review イベントに渡る', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    await btn(wrapper, 'change-request-reject-1').trigger('click')
    await nextTick()

    const textarea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="change-request-review-comment"]')
    expect(textarea).not.toBeNull()
    textarea!.value = '人手が足りません'
    textarea!.dispatchEvent(new Event('input'))
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!.click()
    await nextTick()

    expect(wrapper.emitted('review')).toEqual([[1, 'REJECTED', '人手が足りません']])
  })

  it('AC-I2: 確定ボタンを素早く2回押しても review は1回だけ', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    await btn(wrapper, 'change-request-approve-1').trigger('click')
    await nextTick()

    const confirm = document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!
    confirm.click()
    confirm.click()
    await nextTick()

    expect(wrapper.emitted('review')).toHaveLength(1)
  })
})

describe('ShiftChangeRequestList 取り下げ（AC-I1）', () => {
  it('AC-I1: 処理中の依頼の取り下げボタンは disabled で、押しても withdraw を発火しない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OWN], currentUserId: 5, canReview: false, processingIds: [2] },
    })
    const button = wrapper.find('[data-testid="change-request-withdraw-2"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(wrapper.emitted('withdraw')).toBeUndefined()
  })

  it('AC-I1: 処理中でなければ押下で withdraw を1回発火する', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OWN], currentUserId: 5, canReview: false },
    })
    await wrapper.find('[data-testid="change-request-withdraw-2"]').trigger('click')
    expect(wrapper.emitted('withdraw')).toEqual([[2]])
  })
})

describe('ShiftChangeRequestList 表示（AC-C1 / AC-M3）', () => {
  it('AC-C1: 表ヘッダーに common. で始まる生キーが出ない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    expect(wrapper.text()).not.toMatch(/\bcommon\./)
    expect(wrapper.text()).toContain('Requester')
    expect(wrapper.text()).toContain('Status')
    expect(wrapper.text()).toContain('Actions')
  })

  it('AC-M3: 依頼者列に名前が出て、null は「不明なユーザー」。userId は表示しない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: {
        requests: [OTHERS, row({ id: 3, requestedBy: 90241, requesterName: null })],
        currentUserId: 5,
        canReview: false,
      },
    })
    const text = wrapper.text()
    expect(text).toContain('山田花子')
    expect(text).toContain('Unknown user')
    expect(text).not.toContain('90241')
  })
})
