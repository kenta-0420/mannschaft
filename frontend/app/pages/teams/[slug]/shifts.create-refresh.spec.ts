import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive, ref } from 'vue'
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import ShiftsPage from './shifts.vue'
import ShiftScheduleList from '~/components/shift/ShiftScheduleList.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

/**
 * CMP-260910-1600: 親の作成UIから実一覧の表示までを検査する。
 * API境界・権限の解決結果を制御するFE試験であり、実BE認可/実機の証拠ではない。
 * 作成フォームのButton/InputText/DatePicker/Dialogと一覧はスタブしない。
 */
const route = reactive({ params: { slug: 'team-a' }, query: {} as Record<string, unknown> })
const role = ref('ADMIN')
const shellTeam = ref({ numericId: 10 })
const listSchedules = vi.fn<(teamId: number) => Promise<ShiftScheduleResponse[]>>()
const createSchedule = vi.fn<(...args: unknown[]) => Promise<ShiftScheduleResponse>>()
const success = vi.fn()
const error = vi.fn()
const handleApiError = vi.fn()
const ownedWrappers = new Set<VueWrapper>()
const ownedMounts = new Map<VueWrapper, HTMLElement>()

mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useTeamShellContext', () => () => ({ team: shellTeam }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(role.value === 'ADMIN'),
  isAdminOrDeputy: ref(['ADMIN', 'DEPUTY_ADMIN'].includes(role.value)),
  roleName: role,
  loadPermissions: vi.fn(async () => undefined),
}))
mockNuxtImport('useShiftApi', () => () => ({ listSchedules, createSchedule }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useNotification', () => () => ({ success, error }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

function schedule(id: number, title: string, teamId = 10): ShiftScheduleResponse {
  return {
    id, teamId,
    content: { title, periodType: 'WEEKLY', note: null },
    period: { startDate: '2026-11-09', endDate: '2026-11-15', requestDeadline: null },
    status: { status: 'DRAFT', publishedAt: null, publishedBy: null },
    audit: { createdBy: 1, createdAt: '', updatedAt: '' },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

async function settle() {
  await flushPromises()
  await nextTick()
}

async function mountPage() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  let wrapper: VueWrapper
  try {
    wrapper = await mountSuspended(ShiftsPage, {
      attachTo: host,
      global: {
        components: { ShiftScheduleList },
        // 別タブ/別操作だけを除外し、作成UIと対象一覧は実物を通す。
        stubs: {
          ShiftSwapList: true, ShiftPositionManager: true, ShiftRequestForm: true,
          ShiftReminderButton: true, PageHeader: true,
        },
      },
    })
  } catch (failure) {
    host.remove()
    throw failure
  }
  ownedWrappers.add(wrapper)
  ownedMounts.set(wrapper, host)
  await settle()
  expect(wrapper.findComponent(ShiftScheduleList).exists()).toBe(true)
  return wrapper
}

function unmountOwned(wrapper: VueWrapper) {
  wrapper.unmount()
  ownedWrappers.delete(wrapper)
  ownedMounts.get(wrapper)?.remove()
  ownedMounts.delete(wrapper)
}

afterEach(async () => {
  for (const wrapper of [...ownedWrappers]) unmountOwned(wrapper)
  await settle()
})

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(Button).find(candidate => candidate.props('label') === label)
  if (!found) throw new Error(`操作ボタンがありません: ${label}`)
  return found
}

async function openForm(wrapper: VueWrapper) {
  await button(wrapper, '新規作成').trigger('click')
  await settle()
  const dialog = wrapper.findAllComponents(Dialog).find(candidate => candidate.props('header') === 'シフト表を作成')
  if (!dialog) throw new Error('作成ダイアログがありません')
  expect(dialog.props('visible')).toBe(true)
  await dialog.findComponent(InputText).setValue('新しいシフト表')
  const dates = dialog.findAllComponents(DatePicker)
  expect(dates).toHaveLength(2)
  // 日付手入力の契約は別戦役。実DatePickerの公開modelイベントで有効値を渡す。
  dates[0]!.vm.$emit('update:modelValue', new Date('2026-11-09T03:00:00Z'))
  dates[1]!.vm.$emit('update:modelValue', new Date('2026-11-15T03:00:00Z'))
  await nextTick()
  return dialog
}

beforeEach(() => {
  route.params.slug = 'team-a'
  route.query = {}
  role.value = 'ADMIN'
  shellTeam.value = { numericId: 10 }
  listSchedules.mockReset()
  createSchedule.mockReset()
  success.mockReset()
  error.mockReset()
  handleApiError.mockReset()
  listSchedules.mockResolvedValue([])
  createSchedule.mockResolvedValue(schedule(2, '新しいシフト表'))
})

describe('CMP-260910-1600 作成後の実一覧更新', () => {
  it.each([
    { condition: '空の一覧', initial: [] as ShiftScheduleResponse[] },
    { condition: '既存行あり', initial: [schedule(1, '既存シフト表')] },
  ])('AC-1/2 $conditionから作成するとF5なしで既存行と新規行を各1件表示する', async ({ initial }) => {
    listSchedules.mockResolvedValueOnce(initial).mockResolvedValueOnce([...initial, schedule(2, '新しいシフト表')])
    const wrapper = await mountPage()
    const dialog = await openForm(wrapper)

    await button(dialog, '作成').trigger('click')
    await settle()

    expect(createSchedule).toHaveBeenCalledTimes(1)
    expect(createSchedule).toHaveBeenCalledWith(10, {
      title: '新しいシフト表', startDate: '2026-11-09', endDate: '2026-11-15',
    })
    expect(listSchedules).toHaveBeenCalledTimes(2)
    const titles = wrapper.findComponent(ShiftScheduleList).findAll('p.font-medium').map(row => row.text())
    expect(titles).toEqual([...initial.map(row => row.content.title), '新しいシフト表'])
    expect(dialog.props('visible')).toBe(false)
  })

  it('AC-3 POST失敗では既存一覧を維持し成功通知と追加GETを出さない', async () => {
    listSchedules.mockResolvedValue([schedule(1, '既存シフト表')])
    createSchedule.mockRejectedValueOnce(new Error('作成失敗'))
    const wrapper = await mountPage()
    const dialog = await openForm(wrapper)

    await button(dialog, '作成').trigger('click')
    await settle()

    expect(createSchedule).toHaveBeenCalledTimes(1)
    expect(listSchedules).toHaveBeenCalledTimes(1)
    expect(success).not.toHaveBeenCalled()
    expect(error).toHaveBeenCalledTimes(1)
    expect(dialog.props('visible')).toBe(true)
    expect(wrapper.findComponent(ShiftScheduleList).text()).toContain('既存シフト表')
  })

  it('AC-3 ダイアログ取消ではPOSTも追加GETも行わない', async () => {
    const wrapper = await mountPage()
    const dialog = await openForm(wrapper)

    await button(dialog, 'キャンセル').trigger('click')
    await settle()

    expect(createSchedule).not.toHaveBeenCalled()
    expect(listSchedules).toHaveBeenCalledTimes(1)
    expect(dialog.props('visible')).toBe(false)
  })

  it('AC-4 作成後GET失敗を空状態にせず既存の再試行で復旧しPOSTを再送しない', async () => {
    const failure = new Error('一覧取得失敗')
    listSchedules.mockResolvedValueOnce([]).mockRejectedValueOnce(failure)
      .mockResolvedValueOnce([schedule(2, '新しいシフト表')])
    const wrapper = await mountPage()
    const dialog = await openForm(wrapper)

    await button(dialog, '作成').trigger('click')
    await settle()

    const list = wrapper.findComponent(ShiftScheduleList)
    expect(list.text()).toContain('common.scopeShell.load_error_body')
    expect(list.text()).not.toContain('shift.empty.noSchedules')
    expect(handleApiError).toHaveBeenCalledWith(failure, 'シフト表取得')
    expect(success).toHaveBeenCalledTimes(1)
    expect(error).not.toHaveBeenCalled()
    expect(dialog.props('visible')).toBe(false)

    await button(list, 'common.scopeShell.retry').trigger('click')
    await settle()

    expect(list.text()).toContain('新しいシフト表')
    expect(list.text()).not.toContain('common.scopeShell.load_error_body')
    expect(listSchedules).toHaveBeenCalledTimes(3)
    expect(createSchedule).toHaveBeenCalledTimes(1)
  })

  it('AC-5 一般メンバーには新規作成操作を表示しない', async () => {
    role.value = 'MEMBER'
    const wrapper = await mountPage()

    expect(wrapper.findAllComponents(Button).some(candidate => candidate.props('label') === '新規作成')).toBe(false)
    expect(createSchedule).not.toHaveBeenCalled()
    expect(listSchedules).toHaveBeenCalledTimes(1)
  })

  it.each(['POST', 'GET'] as const)('AC-6 チーム遷移で再mountした後に旧チームの%s応答が届いても混線しない', async phase => {
    const oldCreate = deferred<ShiftScheduleResponse>()
    const oldList = deferred<ShiftScheduleResponse[]>()
    listSchedules.mockResolvedValueOnce([])
    if (phase === 'POST') createSchedule.mockReturnValueOnce(oldCreate.promise)
    else listSchedules.mockReturnValueOnce(oldList.promise)
    const teamA = await mountPage()
    const dialog = await openForm(teamA)
    await button(dialog, '作成').trigger('click')
    await settle()
    expect(createSchedule).toHaveBeenCalledTimes(1)
    if (phase === 'GET') expect(listSchedules).toHaveBeenCalledTimes(2)

    // Nuxt 3.21.11はmatched pathにslugを補間したkeyでページを再mountする。
    // app/app.vueと当該pageにはpageKey/keepalive overrideなし。同一instanceの偽slug変更はしない。
    unmountOwned(teamA)
    route.params.slug = 'team-b'
    shellTeam.value = { numericId: 20 }
    listSchedules.mockResolvedValue([schedule(3, 'チームBのシフト表', 20)])
    const teamB = await mountPage()
    const before = listSchedules.mock.calls.length

    oldCreate.resolve(schedule(2, '旧チームAの新規行'))
    oldList.resolve([schedule(2, '旧チームAの新規行')])
    await settle()

    expect(teamB.findComponent(ShiftScheduleList).findAll('p.font-medium').map(row => row.text()))
      .toEqual(['チームBのシフト表'])
    expect(listSchedules).toHaveBeenLastCalledWith(20)
    expect(listSchedules).toHaveBeenCalledTimes(before)
    expect(createSchedule).toHaveBeenCalledTimes(1)
  })
})
