import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftSwapRequestFormDialog from './ShiftSwapRequestFormDialog.vue'
import type { MemberResponse } from '~/types/member'

const mocks = vi.hoisted(() => ({
  getAllMembers: vi.fn(),
  getMembers: vi.fn(),
  create: vi.fn(),
  slots: vi.fn(),
  handleError: vi.fn(),
  fetchTeams: vi.fn(),
  teams: [] as { id: number; slug: string }[],
}))
mockNuxtImport('useTeamApi', () => () => ({
  getAllMembers: mocks.getAllMembers,
  getMembers: mocks.getMembers,
}))
mockNuxtImport('useTeamStore', () => () => ({
  get myTeams() {
    return mocks.teams
  },
  fetchMyTeamsWithResult: mocks.fetchTeams,
}))
mockNuxtImport('useShiftSwapApi', () => () => ({ createSwapRequest: mocks.create }))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots: mocks.slots }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleError }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

function member(userId: number): MemberResponse {
  return { userId, displayName: `候補${userId}`, avatarUrl: null, roleName: 'MEMBER', joinedAt: '' }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function mountForm() {
  const wrapper = await mountSuspended(ShiftSwapRequestFormDialog, {
    props: { visible: false, slotId: 12, slotDate: '2026-10-22', scheduleId: 4, teamId: '7' },
    global: {
      stubs: {
        Dialog: {
          props: ['visible'],
          template: '<section v-if="visible"><slot/><slot name="footer"/></section>',
        },
        Button: {
          props: ['label', 'disabled'],
          template: '<button :disabled="disabled">{{ label }}</button>',
        },
        Checkbox: {
          props: ['modelValue', 'value'],
          emits: ['update:modelValue'],
          template:
            '<input type="checkbox" :checked="modelValue.includes(value)" @change="$emit(\'update:modelValue\', modelValue.includes(value) ? modelValue.filter(id => id !== value) : [...modelValue, value])">',
        },
        DashboardErrorState: {
          props: ['testid'],
          emits: ['retry'],
          template:
            '<div :data-testid="testid"><button @click="$emit(\'retry\')">再試行</button></div>',
        },
        DashboardEmptyState: { template: '<div data-testid="empty"/>' },
        PageLoading: { template: '<div data-testid="loading"/>' },
      },
    },
  })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}
type Form = Awaited<ReturnType<typeof mountForm>>
function submitButton(wrapper: Form) {
  return wrapper.findAll('button').find((button) => button.text() === 'shift.action.submit')!
}
async function selectLast(wrapper: Form) {
  const inputs = wrapper.findAll('input[type="checkbox"]')
  await inputs.at(-1)!.setValue(true)
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.teams = [
    { id: 7, slug: 'alpha' },
    { id: 8, slug: 'beta' },
  ]
  mocks.getAllMembers.mockResolvedValue({ data: [member(1)] })
  mocks.getMembers.mockResolvedValue({ data: [member(1)] })
  mocks.fetchTeams.mockResolvedValue({ ok: true })
  mocks.slots.mockResolvedValue([])
  mocks.create.mockResolvedValue({})
})

describe('交代依頼の候補者取得', () => {
  it.each([1, 100, 101])('%i人の末尾候補を全件API1要求から選んで提出する', async (count) => {
    mocks.getAllMembers.mockResolvedValue({
      data: Array.from({ length: count }, (_, i) => member(i + 1)),
    })
    const wrapper = await mountForm()
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(count)
    await selectLast(wrapper)
    await submitButton(wrapper).trigger('click')
    expect(mocks.getAllMembers).toHaveBeenCalledExactlyOnceWith('alpha')
    expect(mocks.getMembers).not.toHaveBeenCalled()
    expect(mocks.create).toHaveBeenCalledWith({
      slotId: 12,
      reason: undefined,
      openCall: false,
      targetUserIds: [count],
    })
  })

  it('storeに対象が無ければ所属チームを取得してslugを解決する', async () => {
    mocks.teams = []
    mocks.fetchTeams.mockImplementation(async () => {
      mocks.teams = [{ id: 7, slug: 'alpha' }]
      return { ok: true }
    })
    await mountForm()
    expect(mocks.fetchTeams).toHaveBeenCalledOnce()
    expect(mocks.getAllMembers).toHaveBeenCalledWith('alpha')
  })

  it('候補0件は失敗と区別して未選択の提出を無効にする', async () => {
    mocks.getAllMembers.mockResolvedValue({ data: [] })
    const wrapper = await mountForm()
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="swap-members-error"]').exists()).toBe(false)
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('所属取得成功でも対象チーム不在なら数値IDを誤送信しない', async () => {
    mocks.teams = []
    const wrapper = await mountForm()
    expect(wrapper.find('[data-testid="swap-members-error"]').exists()).toBe(true)
    expect(mocks.getAllMembers).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
  })

  for (const source of ['store', 'members']) {
    it.each([401, 403, 404, 500, 'network'])(
      `${source}取得失敗%sを表示し同じ経路で再試行する`,
      async (status) => {
        const error =
          status === 'network' ? new TypeError('Failed to fetch') : { statusCode: status }
        if (source === 'store') {
          mocks.teams = []
          mocks.fetchTeams
            .mockResolvedValueOnce({ ok: false, error })
            .mockImplementation(async () => {
              mocks.teams = [{ id: 7, slug: 'alpha' }]
              return { ok: true }
            })
        } else mocks.getAllMembers.mockRejectedValueOnce(error)
        const wrapper = await mountForm()
        expect(wrapper.find('[data-testid="swap-members-error"]').exists()).toBe(true)
        expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
        expect(mocks.handleError).toHaveBeenCalledWith(error)
        expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
        await wrapper.get('[data-testid="swap-members-error"] button').trigger('click')
        await flushPromises()
        expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(1)
        expect(wrapper.find('[data-testid="swap-members-error"]').exists()).toBe(false)
      },
    )
  }

  it('閉じて再表示すると候補を再取得し古い選択を消す', async () => {
    const wrapper = await mountForm()
    await selectLast(wrapper)
    await wrapper.setProps({ visible: false })
    mocks.getAllMembers.mockResolvedValue({ data: [member(2)] })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(wrapper.text()).toContain('候補2')
    expect(wrapper.text()).not.toContain('候補1')
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    expect(mocks.getAllMembers).toHaveBeenCalledTimes(2)
  })

  it.each(['success', 'error'])('A→Bの遅延した古い%sを表示しない', async (outcome) => {
    const pending = deferred<{ data: MemberResponse[] }>()
    mocks.getAllMembers
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ data: [member(8)] })
    const wrapper = await mountForm()
    await wrapper.setProps({ teamId: '8' })
    await flushPromises()
    if (outcome === 'success') pending.resolve({ data: [member(7)] })
    else pending.reject({ statusCode: 500 })
    await flushPromises()
    expect(wrapper.text()).toContain('候補8')
    expect(wrapper.text()).not.toContain('候補7')
    expect(wrapper.find('[data-testid="swap-members-error"]').exists()).toBe(false)
    expect(mocks.handleError).not.toHaveBeenCalled()
  })

  it('古いfinallyで新チームのloadingを解除せず古い選択も送信しない', async () => {
    const pending = deferred<{ data: MemberResponse[] }>()
    const wrapper = await mountForm()
    await selectLast(wrapper)
    mocks.getAllMembers.mockReturnValueOnce(pending.promise)
    await wrapper.setProps({ teamId: '8' })
    await flushPromises()
    expect(wrapper.find('[data-testid="loading"]').exists()).toBe(true)
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
    pending.resolve({ data: [member(8)] })
    await flushPromises()
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('Aの遅延応答が終わってもBの取得中表示を維持する', async () => {
    const first = deferred<{ data: MemberResponse[] }>()
    const second = deferred<{ data: MemberResponse[] }>()
    mocks.getAllMembers.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = await mountForm()
    await wrapper.setProps({ teamId: '8' })
    first.resolve({ data: [member(7)] })
    await flushPromises()
    expect(wrapper.find('[data-testid="loading"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)
    second.resolve({ data: [member(8)] })
    await flushPromises()
    expect(wrapper.text()).toContain('候補8')
  })

  it('閉じた後の遅延失敗を通知せず再表示の候補を維持する', async () => {
    const pending = deferred<{ data: MemberResponse[] }>()
    mocks.getAllMembers
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ data: [member(2)] })
    const wrapper = await mountForm()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    pending.reject({ statusCode: 403 })
    await flushPromises()
    expect(wrapper.text()).toContain('候補2')
    expect(mocks.handleError).not.toHaveBeenCalled()
  })

  it('TEMPLATEの昼シフト宛先と送信内容を維持する', async () => {
    mocks.slots.mockResolvedValue([
      {
        time: { slotDate: '2026-10-22', startTime: '09:00' },
        assignedUserIds: [31, 32],
      },
      {
        time: { slotDate: '2026-10-22', startTime: '17:00' },
        assignedUserIds: [33],
      },
    ])
    const wrapper = await mountForm()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'shift.swap.recipientMode.template')!
      .trigger('click')
    await submitButton(wrapper).trigger('click')
    expect(mocks.create).toHaveBeenCalledWith({
      slotId: 12,
      reason: undefined,
      openCall: false,
      targetUserIds: [31, 32],
    })
  })

  it('OPEN_CALLの送信内容を維持する', async () => {
    const wrapper = await mountForm()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'shift.swap.recipientMode.openCall')!
      .trigger('click')
    await submitButton(wrapper).trigger('click')
    expect(mocks.create).toHaveBeenCalledWith({ slotId: 12, reason: undefined, openCall: true })
  })
})
