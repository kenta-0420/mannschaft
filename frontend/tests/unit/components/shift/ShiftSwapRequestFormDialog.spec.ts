import { defineComponent, h, type PropType } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftSwapRequestFormDialog from '~/components/shift/ShiftSwapRequestFormDialog.vue'

const mockResolveContextByTeamId = vi.fn()
const mockGetMembers = vi.fn()
const mockListSlots = vi.fn()
const mockCreateSwapRequest = vi.fn()
const mountedWrappers: Array<{ unmount: () => void }> = []

vi.mock('~/composables/match/useMatchOrgContext', () => ({
  useMatchOrgContext: () => ({ resolveContextByTeamId: mockResolveContextByTeamId }),
}))

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTeamApi', () => () => ({ getMembers: mockGetMembers }))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots: mockListSlots }))
mockNuxtImport('useShiftSwapApi', () => () => ({ createSwapRequest: mockCreateSwapRequest }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    currentUser: { id: 7 },
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
    logout: vi.fn(),
  }),
}))

const DialogStub = defineComponent({
  name: 'Dialog',
  props: { visible: Boolean, header: String },
  emits: ['update:visible'],
  setup(_props, { slots }) {
    return () => h('div', [slots.default?.(), slots.footer?.()])
  },
})

const ButtonStub = defineComponent({
  name: 'Button',
  props: { label: String, disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup(props, { emit }) {
    return () =>
      h(
        'button',
        {
          type: 'button',
          disabled: props.disabled,
          onClick: (event: MouseEvent) => emit('click', event),
        },
        props.label,
      )
  },
})

const CheckboxStub = defineComponent({
  name: 'Checkbox',
  props: {
    modelValue: { type: Array as PropType<number[]>, default: () => [] },
    value: { type: Number, required: true },
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h('input', {
        type: 'checkbox',
        checked: props.modelValue.includes(props.value),
        onChange: () =>
          emit(
            'update:modelValue',
            props.modelValue.includes(props.value)
              ? props.modelValue.filter((id) => id !== props.value)
              : [...props.modelValue, props.value],
          ),
      })
  },
})

const member = (userId: number, displayName: string) => ({
  userId,
  displayName,
  avatarUrl: null,
  roleName: 'ADMIN',
  joinedAt: '2026-10-01T00:00:00',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function shiftSlot(scheduleId: number, assignedUserIds: number[]) {
  return {
    id: scheduleId,
    scheduleId,
    time: { slotDate: '2026-10-12', startTime: '08:00', endTime: '12:00', endsNextDay: false },
    position: { positionId: null, positionName: null, requiredCount: 1 },
    assignedUserIds,
    assignmentMasked: false,
    note: null,
  }
}

async function mountDialog(teamId = 41, scheduleId = 501) {
  const wrapper = await mountSuspended(ShiftSwapRequestFormDialog, {
    props: { visible: false, slotId: 601, slotDate: '2026-10-12', scheduleId, teamId },
    global: {
      stubs: {
        Dialog: DialogStub,
        Button: ButtonStub,
        Checkbox: CheckboxStub,
        Textarea: true,
      },
    },
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('ShiftSwapRequestFormDialog.vue', () => {
  beforeEach(() => {
    mockResolveContextByTeamId.mockReset()
    mockGetMembers.mockReset()
    mockListSlots.mockReset()
    mockCreateSwapRequest.mockReset()
    mockListSlots.mockResolvedValue([])
    mockCreateSwapRequest.mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of mountedWrappers.splice(0)) wrapper.unmount()
  })

  it('数値 teamId を slug に解決し、SPECIFIC 候補を選ぶと送信可能になる', async () => {
    mockResolveContextByTeamId.mockResolvedValue({ teamId: 41, teamSlug: 'team-four-one' })
    mockGetMembers.mockResolvedValue({ data: [member(3, 'Admin Three')] })
    const wrapper = await mountDialog()

    await wrapper.setProps({ visible: true })
    await flushPromises()

    expect(mockResolveContextByTeamId).toHaveBeenCalledWith(41)
    expect(mockGetMembers).toHaveBeenCalledWith('team-four-one', { size: 100 })
    expect(wrapper.text()).toContain('Admin Three')
    expect(wrapper.text()).toContain('label.optional')
    expect(wrapper.text()).not.toContain('common.label.optional')

    const submitButton = wrapper
      .findAllComponents(ButtonStub)
      .find((button) => button.props('label') === 'shift.action.submit')
    expect(submitButton?.props('disabled')).toBe(true)

    await wrapper.findComponent(CheckboxStub).trigger('change')
    await flushPromises()
    expect(submitButton?.props('disabled')).toBe(false)
  })

  it('再表示後に旧チーム・旧scheduleの遅い応答が新しい候補と枠を上書きしない', async () => {
    const oldMembers = deferred<{ data: ReturnType<typeof member>[] }>()
    const oldSlots = deferred<ReturnType<typeof shiftSlot>[]>()
    mockResolveContextByTeamId
      .mockResolvedValueOnce({ teamId: 41, teamSlug: 'team-four-one' })
      .mockResolvedValueOnce({ teamId: 42, teamSlug: 'team-four-two' })
    mockGetMembers
      .mockReturnValueOnce(oldMembers.promise)
      .mockResolvedValueOnce({ data: [member(32, 'Team Two Member')] })
    mockListSlots
      .mockReturnValueOnce(oldSlots.promise)
      .mockResolvedValueOnce([shiftSlot(502, [98])])
    const wrapper = await mountDialog(41, 501)

    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(mockGetMembers).toHaveBeenCalledWith('team-four-one', { size: 100 })
    expect(mockListSlots).toHaveBeenCalledWith(501)

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ teamId: 42, scheduleId: 502, visible: true })
    await flushPromises()

    expect(mockGetMembers).toHaveBeenNthCalledWith(2, 'team-four-two', { size: 100 })
    expect(mockListSlots).toHaveBeenNthCalledWith(2, 502)
    expect(wrapper.text()).toContain('Team Two Member')

    const templateButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'shift.swap.recipientMode.template')
    await templateButton?.trigger('click')
    await flushPromises()

    oldMembers.resolve({ data: [member(31, 'Old Team Member')] })
    oldSlots.resolve([shiftSlot(501, [])])
    await flushPromises()
    expect(wrapper.text()).toMatch(/shift\.swap\.targetUsers\.dayShift\s*\(1\)/)

    const specificButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'shift.swap.recipientMode.specific')
    await specificButton?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Team Two Member')
    expect(wrapper.text()).not.toContain('Old Team Member')
  })

  it('OPEN_CALL は対象一覧を選ばず従来どおり送信できる', async () => {
    mockResolveContextByTeamId.mockResolvedValue({ teamId: 41, teamSlug: 'team-four-one' })
    mockGetMembers.mockResolvedValue({ data: [] })
    const wrapper = await mountDialog()

    await wrapper.setProps({ visible: true })
    await flushPromises()
    const openCallButton = wrapper
      .findAll('button')
      .find((button) => button.text() === 'shift.swap.recipientMode.openCall')
    await openCallButton?.trigger('click')
    await flushPromises()

    const submitButton = wrapper
      .findAllComponents(ButtonStub)
      .find((button) => button.props('label') === 'shift.action.submit')
    expect(submitButton?.props('disabled')).toBe(false)
    await submitButton?.trigger('click')

    expect(mockCreateSwapRequest).toHaveBeenCalledWith({
      slotId: 601,
      reason: undefined,
      openCall: true,
    })
  })
})
