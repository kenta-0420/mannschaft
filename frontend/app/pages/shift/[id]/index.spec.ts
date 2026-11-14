import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { useNuxtApp } from '#app'
import jaCommon from '~/locales/ja/common.json'
import jaShift from '~/locales/ja/shift.json'
import type { ShiftScheduleResponse, ShiftScheduleStatus } from '~/types/shift'
import { useTeamStore } from '~/stores/useTeamStore'
import ShiftDetailPage from './index.vue'

// CMP-260910-1604: ページ/store/Buttonは実物。通信・確認サービス・表示副作用の境界だけを差し替える。
const mocks = vi.hoisted(() => ({
  api: vi.fn(), getSchedule: vi.fn(), listSlots: vi.fn(), transitionStatus: vi.fn(),
  updateSchedule: vi.fn(), deleteSchedule: vi.fn(), confirmRequire: vi.fn(),
  success: vi.fn(), handleApiError: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { id: '399' }, path: '/shift/399', query: {} }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useShiftApi', () => () => ({
  getSchedule: mocks.getSchedule, updateSchedule: mocks.updateSchedule,
  transitionStatus: mocks.transitionStatus, deleteSchedule: mocks.deleteSchedule,
}))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots: mocks.listSlots }))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.confirmRequire }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

type Prompt = {
  message?: string
  header?: string
  icon?: string
  acceptLabel?: string
  rejectLabel?: string
  accept?: () => void | Promise<void>
  reject?: () => void | Promise<void>
}

function schedule(status: ShiftScheduleStatus = 'ADJUSTING'): ShiftScheduleResponse {
  return {
    id: 399, teamId: 12,
    content: { title: '公開確認の試練', periodType: 'WEEKLY', note: null },
    period: { startDate: '2026-11-01', endDate: '2026-11-07', requestDeadline: null },
    status: { status, publishedAt: null, publishedBy: null },
    audit: { createdBy: 1, createdAt: '2026-11-01T00:00:00', updatedAt: '2026-11-01T00:00:00' },
  }
}

function team(role = 'ADMIN', id = 12) {
  return { id, slug: 'team-' + id, name: '試練チーム', nickname1: null, iconUrl: null, role, template: 'GENERAL', memberCount: 1 }
}

async function mountPage(status: ShiftScheduleStatus = 'ADJUSTING', role = 'ADMIN', teamId = 12) {
  mocks.api.mockImplementation((url: string) => {
    if (url === '/api/v1/me/teams') return Promise.resolve({ data: [team(role, teamId)] })
    throw new Error('unexpected API boundary')
  })
  mocks.getSchedule.mockResolvedValue(schedule(status))
  const wrapper = await mountSuspended(ShiftDetailPage, {
    global: { stubs: { ShiftReminderButton: true } },
  })
  await flushPromises()
  return wrapper
}

type Page = Awaited<ReturnType<typeof mountPage>>
function button(page: Page, key: string) {
  const label = page.vm.$t(key)
  const found = page.findAll('button').find(item => item.text() === label)
  expect(found, '実ページの操作ボタン: ' + key).toBeDefined()
  return found!
}
function prompt(): Prompt {
  expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
  return mocks.confirmRequire.mock.calls[0]![0] as Prompt
}

beforeEach(async () => {
  // lazy locale取得に依存せず、実Nuxt/vue-i18nへ製品の日本語辞書を読み込む。
  const i18n = useNuxtApp().$i18n
  await i18n.setLocale('ja')
  i18n.mergeLocaleMessage('ja', { ...jaCommon, ...jaShift })
  useTeamStore().clear()
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.listSlots.mockResolvedValue([])
  mocks.transitionStatus.mockImplementation(async (_id: number, status: ShiftScheduleStatus) => schedule(status))
})

describe('シフト詳細の公開前確認', () => {
  it('AC1 初期表示だけでは公開APIも確認も送らない', async () => {
    const page = await mountPage()
    expect(button(page, 'shift.detail.publish').exists()).toBe(true)
    expect(mocks.getSchedule).toHaveBeenCalledWith(399)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
  })

  it('AC1 公開クリックはチーム画面と同じ確認を示し承認前にAPIを送らない', async () => {
    const page = await mountPage()
    await button(page, 'shift.detail.publish').trigger('click')
    const confirmation = prompt()
    expect(confirmation.message, '公開確認本文').toBe('このシフトを公開しますか？メンバーに通知されます。')
    expect(confirmation.header, '公開確認見出し').toBe('シフト公開の確認')
    expect(confirmation.icon, '公開確認アイコン').toBe('pi pi-send')
    expect(confirmation.acceptLabel, '公開承認ラベル').toBe('公開する')
    expect(confirmation.rejectLabel, '公開取消ラベル').toBe('キャンセル')
    expect(confirmation.accept).toBeTypeOf('function')
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
  })

  it('AC2 公開確認を取消してもAPIを送らず再び公開確認を開ける', async () => {
    const page = await mountPage()
    await button(page, 'shift.detail.publish').trigger('click')
    const confirmation = prompt()
    // 確認サービスの正常な取消契約。reject未指定なら副作用なしで閉じる。
    await confirmation.reject?.()
    await flushPromises()
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    await button(page, 'shift.detail.publish').trigger('click')
    expect(mocks.confirmRequire).toHaveBeenCalledTimes(2)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
  })

  it('AC3 公開確認の明示承認1回で同じシフトをPUBLISHEDへ1回だけ遷移する', async () => {
    const page = await mountPage()
    await button(page, 'shift.detail.publish').trigger('click')
    const confirmation = prompt()
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    await confirmation.accept?.()
    await flushPromises()
    expect(mocks.transitionStatus).toHaveBeenCalledTimes(1)
    expect(mocks.transitionStatus).toHaveBeenCalledWith(399, 'PUBLISHED')
    expect(mocks.success).toHaveBeenCalledTimes(1)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(button(page, 'shift.detail.archive').exists()).toBe(true)
  })

  it('AC4 承認後のAPI失敗を既存エラー処理へ渡し偽成功せず再確認できる', async () => {
    const failure = new Error('publish request failed')
    const page = await mountPage()
    mocks.transitionStatus.mockRejectedValueOnce(failure)
    await button(page, 'shift.detail.publish').trigger('click')
    const confirmation = prompt()
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    await confirmation.accept?.()
    await flushPromises()
    expect(mocks.transitionStatus).toHaveBeenCalledTimes(1)
    expect(mocks.handleApiError).toHaveBeenCalledWith(failure)
    expect(mocks.success).not.toHaveBeenCalled()
    await button(page, 'shift.detail.publish').trigger('click')
    expect(mocks.confirmRequire).toHaveBeenCalledTimes(2)
    expect(mocks.transitionStatus).toHaveBeenCalledTimes(1)
  })

  it('AC5 DRAFTからCOLLECTINGは確認を増やさず既存どおり遷移する', async () => {
    const page = await mountPage('DRAFT')
    await button(page, 'shift.detail.startCollecting').trigger('click')
    await flushPromises()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
    expect(mocks.transitionStatus).toHaveBeenCalledExactlyOnceWith(399, 'COLLECTING')
  })

  it('AC5 COLLECTINGからADJUSTINGは確認を増やさず既存どおり遷移する', async () => {
    const page = await mountPage('COLLECTING')
    await button(page, 'shift.detail.startAdjusting').trigger('click')
    await flushPromises()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
    expect(mocks.transitionStatus).toHaveBeenCalledExactlyOnceWith(399, 'ADJUSTING')
  })

  it('AC5 PUBLISHEDからARCHIVEDは確認を増やさず既存どおり遷移する', async () => {
    const page = await mountPage('PUBLISHED')
    await button(page, 'shift.detail.archive').trigger('click')
    await flushPromises()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
    expect(mocks.transitionStatus).toHaveBeenCalledExactlyOnceWith(399, 'ARCHIVED')
  })

  it('AC5 ARCHIVEDでは状態遷移ボタンもAPI呼出もない', async () => {
    const page = await mountPage('ARCHIVED')
    expect(page.findAll('button').some(item => ['shift.detail.publish', 'shift.detail.archive'].map(key => page.vm.$t(key)).includes(item.text()))).toBe(false)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
  })

  it('AC5 取得失敗でscheduleがnullなら公開操作を表示しない', async () => {
    const failure = new Error('schedule request failed')
    mocks.api.mockResolvedValue({ data: [team()] })
    mocks.getSchedule.mockRejectedValueOnce(failure)
    const page = await mountSuspended(ShiftDetailPage, { global: { stubs: { ShiftReminderButton: true } } })
    await flushPromises()
    expect(page.findAll('button').some(item => item.text() === page.vm.$t('shift.detail.publish'))).toBe(false)
    expect(mocks.handleApiError).toHaveBeenCalledWith(failure)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
  })

  it('AC6 MEMBERには公開操作を表示せず確認もAPIも送らない', async () => {
    const page = await mountPage('ADJUSTING', 'MEMBER')
    expect(page.findAll('button').some(item => item.text() === page.vm.$t('shift.detail.publish'))).toBe(false)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
  })

  it('AC6 別チームのADMINには対象シフトの公開操作を表示しない', async () => {
    const page = await mountPage('ADJUSTING', 'ADMIN', 99)
    expect(page.findAll('button').some(item => item.text() === page.vm.$t('shift.detail.publish'))).toBe(false)
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).not.toHaveBeenCalled()
  })

  it('AC6 DEPUTY_ADMINも既存公開権限を保ち承認前はAPIを送らない', async () => {
    const page = await mountPage('ADJUSTING', 'DEPUTY_ADMIN')
    await button(page, 'shift.detail.publish').trigger('click')
    prompt()
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
  })

  it('AC6 SYSTEM_ADMINも既存公開権限を保ち承認前はAPIを送らない', async () => {
    const page = await mountPage('ADJUSTING', 'SYSTEM_ADMIN')
    await button(page, 'shift.detail.publish').trigger('click')
    prompt()
    expect(mocks.transitionStatus).not.toHaveBeenCalled()
  })
})
