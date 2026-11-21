import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import DistributionsPage from '~/pages/committees/[id]/distributions.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/committees/[id]/distributions.vue` は配布履歴取得が失敗しても
 * `distributions` を空配列にリセットするだけで、空状態（「配布履歴はありません」）へ
 * そのまま落ちていた。エラー専用状態（DashboardErrorState / committee-distributions-error-state）
 * で修正した。
 *
 * 検証観点:
 *   CD-001 取得失敗時に committee-distributions-error-state が描画される
 *   CD-002（対照）取得成功・0件時はエラー状態を出さない
 *   CD-003 再試行は初回と同じ取得処理（listDistributions）を呼ぶ
 */

const listDistributions = vi.fn()
const getCommittee = vi.fn()
const distribute = vi.fn()
const notifySuccess = vi.fn()
const handleApiError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { id: '1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({ listDistributions, getCommittee, distribute }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

beforeAll(async () => {
  getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'MEMBER' } })
  listDistributions.mockResolvedValue({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
  const warmup = await mountSuspended(DistributionsPage)
  warmup.unmount()
})

describe('pages/committees/[id]/distributions.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listDistributions.mockReset()
    getCommittee.mockReset()
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'MEMBER' } })
  })

  it('CD-001: 取得失敗時に committee-distributions-error-state が描画される', async () => {
    listDistributions.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(true)
  })

  it('CD-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listDistributions.mockResolvedValue({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(false)
  })

  it('CD-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    listDistributions.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(true)

    listDistributions.mockResolvedValueOnce({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
    const callsBefore = listDistributions.mock.calls.length
    await wrapper.find('[data-testid="committee-distributions-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listDistributions.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="committee-distributions-error-state"]').exists()).toBe(false)
  })
})

describe('pages/committees/[id]/distributions.vue — 検分指摘（CMP-260930-0231）', () => {
  type Vm = { form: Record<string, unknown>; submitDistribution: () => Promise<void> }
  beforeEach(() => {
    listDistributions.mockReset()
    getCommittee.mockReset()
    distribute.mockReset()
    notifySuccess.mockReset()
    handleApiError.mockReset()
    listDistributions.mockResolvedValue({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'CHAIR' } })
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('CD-011: 確認期限が過去だと送信せず、利用者に分かる文言で拒否する', async () => {
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    await wrapper.find('[data-testid="distribution-open-button"]').trigger('click')
    await flushMicrotasks()
    const vm = wrapper.vm as unknown as Vm
    vm.form.customTitle = '連絡'
    vm.form.confirmationMode = 'REQUIRED'
    vm.form.confirmationDeadlineAt = new Date(Date.now() - 60 * 60 * 1000)
    await vm.submitDistribution()
    await flushMicrotasks()

    expect(distribute).not.toHaveBeenCalled()
    expect(globalThis.confirm).not.toHaveBeenCalled()
    expect(document.body.querySelector('[data-testid="distribution-form-error"]')?.textContent).toContain('in the past')
  })

  it('CD-012: 連打しても API は1回だけ呼ばれる', async () => {
    let resolve: (v: unknown) => void = () => {}
    distribute.mockReturnValue(new Promise((r) => { resolve = r }))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    const vm = wrapper.vm as unknown as Vm
    vm.form.customTitle = '連絡'
    const first = vm.submitDistribution()
    const second = vm.submitDistribution()
    resolve({ data: customLog })
    await Promise.all([first, second])
    expect(distribute).toHaveBeenCalledTimes(1)
  })

  it('CD-013: ボタン押下→ダイアログ→確認→API呼び出しの経路を通る', async () => {
    distribute.mockResolvedValue({ data: customLog })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    await wrapper.find('[data-testid="distribution-open-button"]').trigger('click')
    await flushMicrotasks()

    const vm = wrapper.vm as unknown as Vm
    vm.form.customTitle = '連絡'
    await flushMicrotasks()
    const submit = document.body.querySelector('[data-testid="distribution-submit-button"]') as HTMLElement | null
    expect(submit).not.toBeNull()
    submit!.click()
    await flushMicrotasks()

    expect(globalThis.confirm).toHaveBeenCalled()
    expect(distribute).toHaveBeenCalledTimes(1)
    expect(distribute.mock.calls[0][1]).toMatchObject({ contentType: 'CUSTOM_MESSAGE', customTitle: '連絡' })
  })

  it('CD-014: 画面の配信種別は CUSTOM_MESSAGE のみ（対象ID入力欄を持たない）', async () => {
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    await wrapper.find('[data-testid="distribution-open-button"]').trigger('click')
    await flushMicrotasks()
    expect(document.body.querySelector('[data-testid="distribution-content-id"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="distribution-content-type"]')).toBeNull()
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}

const meta = { page: 0, size: 20, total: 2, totalPages: 1 }
const customLog = {
  id: 10, committeeId: 1, contentType: 'CUSTOM_MESSAGE', contentId: null,
  customTitle: '次回会合の議題について', customBody: '議題は3点です',
  targetScope: 'COMMITTEE_ONLY', announcementEnabled: true, confirmationMode: 'NONE',
  confirmableNotificationId: null, announcementFeedIds: [1, 2], createdAt: '2026-05-01T10:30:00',
}
const surveyLog = {
  id: 11, committeeId: 1, contentType: 'SURVEY_RESULT', contentId: 33,
  customTitle: null, customBody: null,
  targetScope: 'PARENT_ORG', announcementEnabled: false, confirmationMode: 'REQUIRED',
  confirmableNotificationId: 777, announcementFeedIds: [], createdAt: '2026-05-02T09:00:00',
}

describe('pages/committees/[id]/distributions.vue — 履歴の描画（CMP-260930-0231）', () => {
  beforeEach(() => {
    listDistributions.mockReset()
    getCommittee.mockReset()
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'MEMBER' } })
  })

  it('CD-004: カスタムメッセージ行は件名・本文・日時・宛先範囲を描画し、件名を重複させない', async () => {
    listDistributions.mockResolvedValue({ data: [customLog], meta })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    const row = wrapper.find('[data-testid="distribution-row-10"]')
    expect(row.exists()).toBe(true)
    expect(row.text().split('次回会合の議題について').length - 1).toBe(1)
    expect(row.text()).toContain('議題は3点です')
    expect(row.text()).toMatch(/2026\/05\/01 \d{2}:\d{2}/)
    expect(row.text()).not.toContain('CUSTOM_MESSAGE')
    expect(row.text()).not.toContain('COMMITTEE_ONLY')
  })

  it('CD-005: 件名の無い行（アンケート結果）も種別と対象IDで空行にならない', async () => {
    listDistributions.mockResolvedValue({ data: [surveyLog], meta })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()

    const row = wrapper.find('[data-testid="distribution-row-11"]')
    expect(row.text()).toContain('Survey result')
    expect(row.text()).toContain('#33')
    expect(row.text()).toMatch(/2026\/05\/02 \d{2}:\d{2}/)
    expect(row.text()).not.toContain('SURVEY_RESULT')
    expect(row.text()).not.toContain('PARENT_ORG')
    expect(row.text()).not.toContain('REQUIRED')
  })
})

describe('pages/committees/[id]/distributions.vue — 配信操作（CMP-260930-0231）', () => {
  beforeEach(() => {
    listDistributions.mockReset()
    getCommittee.mockReset()
    distribute.mockReset()
    notifySuccess.mockReset()
    handleApiError.mockReset()
    listDistributions.mockResolvedValue({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('CD-006: 一般メンバー（MEMBER）には配信ボタンを出さない', async () => {
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'MEMBER' } })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="distribution-open-button"]').exists()).toBe(false)
  })

  it.each(['CHAIR', 'VICE_CHAIR', 'SECRETARY'])('CD-007: %s には配信ボタンを出す', async (role) => {
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: role } })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="distribution-open-button"]').exists()).toBe(true)
  })

  it('CD-008: カスタムメッセージ配信が POST 契約どおりに呼ばれ、成功後に履歴を再取得する', async () => {
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'CHAIR', defaultDistributionScope: 'PARENT_ORG', defaultAnnouncementEnabled: true, defaultConfirmationMode: 'OPTIONAL' } })
    distribute.mockResolvedValue({ data: customLog })
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    await wrapper.find('[data-testid="distribution-open-button"]').trigger('click')
    await flushMicrotasks()

    const vm = wrapper.vm as unknown as { form: Record<string, unknown>; submitDistribution: () => Promise<void> }
    vm.form.contentType = 'CUSTOM_MESSAGE'
    vm.form.customTitle = '連絡'
    vm.form.customBody = '本文'
    const listCalls = listDistributions.mock.calls.length
    await vm.submitDistribution()
    await flushMicrotasks()

    expect(globalThis.confirm).toHaveBeenCalled()
    expect(distribute).toHaveBeenCalledTimes(1)
    const [id, body] = distribute.mock.calls[0]
    expect(id).toBe(1)
    expect(body).toMatchObject({
      contentType: 'CUSTOM_MESSAGE', customTitle: '連絡', customBody: '本文',
      targetScope: 'PARENT_ORG', announcementEnabled: true, confirmationMode: 'OPTIONAL',
    })
    expect(notifySuccess).toHaveBeenCalled()
    expect(listDistributions.mock.calls.length).toBe(listCalls + 1)
  })

  it('CD-009: 確認ダイアログで取り消すと API を呼ばない', async () => {
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'CHAIR' } })
    vi.stubGlobal('confirm', vi.fn(() => false))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    const vm = wrapper.vm as unknown as { form: Record<string, unknown>; submitDistribution: () => Promise<void> }
    vm.form.contentType = 'CUSTOM_MESSAGE'
    vm.form.customTitle = '連絡'
    await vm.submitDistribution()
    expect(distribute).not.toHaveBeenCalled()
  })

  it('CD-010: 配信失敗は握りつぶさず handleApiError に渡し、成功通知を出さない', async () => {
    getCommittee.mockResolvedValue({ data: { id: 1, myRole: 'CHAIR' } })
    distribute.mockRejectedValue(new Error('403'))
    const wrapper = await mountSuspended(DistributionsPage)
    await flushMicrotasks()
    const vm = wrapper.vm as unknown as { form: Record<string, unknown>; submitDistribution: () => Promise<void> }
    vm.form.contentType = 'CUSTOM_MESSAGE'
    vm.form.customTitle = '連絡'
    await vm.submitDistribution()
    await flushMicrotasks()
    expect(handleApiError).toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()
  })
})