import { defineComponent, h, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarWeekGrid from '~/components/schedule/CalendarWeekGrid.vue'
import ScheduleEventForm from '~/components/schedule/ScheduleEventForm.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import '~/plugins/dayjs'

/**
 * CMP-261101-1404 — 秋の重複時刻（America/New_York 2026-11-01 01:00〜02:00）を、
 * 週グリッドで選んでからフォームで保存するまで通して、実行日に依存せず 90分になること。
 *
 * 週グリッドが 01:30(EDT)〜02:00(EST) = 90分の範囲を emit しても、フォームの保存が
 * dayjs.tz(壁時計文字列, tz) で再解決すると、実行日が切替後（EST）のとき 01:30 が EST 側へ
 * 解決され 30分になる（Codex 検分 P2）。画面と保存で同じ部品（~/utils/wallClock）を使うことを固定する。
 * 実行日は vi.setSystemTime で切替前・切替当日・切替後・数日後に固定する。
 */

const scheduleApiMock = {
  createSchedule: vi.fn(),
  createPersonalSchedule: vi.fn(),
  updateSchedule: vi.fn(),
  updatePersonalSchedule: vi.fn(),
  getSchedule: vi.fn(),
  getMyScheduleDetail: vi.fn(),
}
const notificationMock = { success: vi.fn(), error: vi.fn() }
const errorHandlerMock = { handleApiError: vi.fn(), getFieldErrors: vi.fn(() => ({})) }
const googleCalendarMock = { googleSyncEnabled: ref(false), fetchPersonalSyncStatus: vi.fn() }

vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => scheduleApiMock }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => errorHandlerMock }))
vi.mock('~/composables/useGoogleCalendarApi', () => ({ useGoogleCalendarApi: () => googleCalendarMock }))

const authStoreStub = { user: { timezone: 'America/New_York' } }
mockNuxtImport('useAuthStore', () => () => authStoreStub)
mockNuxtImport('useHolidays', () => () => ({ getHoliday: () => null }))

const PopoverStub = defineComponent({
  setup(_props, { slots, expose }) {
    expose({ show: () => {}, hide: () => {} })
    return () => slots.default?.()
  },
})
const ScheduleListRowStub = defineComponent({
  props: { event: { type: Object as () => CalendarEventItem, required: true } },
  setup(props) {
    return () => h('div', props.event.title)
  },
})
const DialogStub = defineComponent({
  name: 'Dialog',
  props: { visible: Boolean, header: String },
  emits: ['update:visible', 'hide'],
  setup(_props, { slots, attrs }) {
    return () => h('div', { ...attrs }, [slots.default?.(), slots.footer?.()])
  },
})
const BasicFieldsStub = defineComponent({
  name: 'ScheduleEventBasicFields',
  props: { form: { type: Object, required: true } },
  emits: ['update:form'],
  setup(props, { emit }) {
    return () => h('input', {
      'data-testid': 'title-input',
      value: (props.form as { title: string }).title,
      onInput: (e: Event) => emit('update:form', { ...(props.form as object), title: (e.target as HTMLInputElement).value }),
    })
  },
})
const ButtonStub = defineComponent({
  name: 'Button',
  props: { label: String },
  emits: ['click'],
  setup(props, { emit, attrs }) {
    return () => h('button', { ...attrs, type: 'button', onClick: (e: Event) => emit('click', e) }, props.label)
  },
})

const MIN_H = 48 / 60
const GRID_LEFT = 100
const GRID_TOP = 200
const COL_W = 70
const yOf = (minutes: number) => GRID_TOP + minutes * MIN_H
const xOf = (dayIndex: number) => GRID_LEFT + dayIndex * COL_W + COL_W / 2
function pointerEvent(type: string, init: { clientX: number, clientY: number }): Event {
  const evt = new Event(type, { bubbles: true, cancelable: true })
  return Object.assign(evt, { clientX: init.clientX, clientY: init.clientY, pointerType: 'mouse', button: 0 })
}

const mounted: Array<{ unmount: () => void }> = []
beforeEach(() => {
  setActivePinia(createPinia())
  scheduleApiMock.createPersonalSchedule.mockReset().mockResolvedValue({ data: { id: 1 } })
})
afterEach(() => {
  vi.useRealTimers()
  while (mounted.length > 0) mounted.pop()?.unmount()
})

/** 2026-11-01(日) の列で 01:30〜02:00 をドラッグし、週グリッドが emit した範囲を返す。 */
async function selectOnGrid(): Promise<[string, string]> {
  const wrapper = await mountSuspended(CalendarWeekGrid, {
    props: { weekStart: '2026-11-01', events: [] as CalendarEventItem[] },
    attachTo: document.body,
    global: { stubs: { Button: true, Popover: PopoverStub, ScheduleListRow: ScheduleListRowStub } },
  })
  mounted.push(wrapper)
  const columns = wrapper.get('[data-testid="week-grid-columns"]').element
  const rect = {
    left: GRID_LEFT, top: GRID_TOP, right: GRID_LEFT + COL_W * 7, bottom: GRID_TOP + 24 * 48,
    width: COL_W * 7, height: 24 * 48, x: GRID_LEFT, y: GRID_TOP, toJSON: () => ({}),
  }
  columns.getBoundingClientRect = () => rect as DOMRect
  columns.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(0), clientY: yOf(90) }))
  window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(0), clientY: yOf(120) }))
  window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(0), clientY: yOf(120) }))
  await wrapper.vm.$nextTick()
  const emitted = wrapper.emitted('rangeSelect')
  expect(emitted).toBeDefined()
  return emitted![emitted!.length - 1] as [string, string]
}

/** 週グリッドの範囲をフォームの初期値に渡して保存し、API へ渡された開始・終了を返す。 */
async function saveOnForm(startAt: string, endAt: string): Promise<{ startAt: string, endAt: string }> {
  const wrapper = await mountSuspended(ScheduleEventForm, {
    props: { visible: false, scopeType: 'team', scopeId: '', isPersonal: true, initialStartAt: startAt, initialEndAt: endAt },
    global: {
      stubs: {
        Dialog: DialogStub, ScheduleEventBasicFields: BasicFieldsStub, ScheduleEventScopeSelector: true,
        ScheduleTargetPicker: true, ScheduleEventRecurrenceInput: true, ScheduleEventReminderInput: true,
        ScheduleEventScheduledAttachmentInput: true, ScheduleEventColorPicker: true,
        Checkbox: true, Textarea: true, Button: ButtonStub,
      },
    },
  })
  mounted.push(wrapper)
  await wrapper.setProps({ visible: true })
  await flushPromises()
  await wrapper.get('[data-testid="title-input"]').setValue('秋の重複を跨ぐ予定')
  const submit = wrapper.findAll('button').find(b => b.text() === wrapper.vm.$t('button.create'))!
  await submit.trigger('click')
  await flushPromises()
  expect(scheduleApiMock.createPersonalSchedule).toHaveBeenCalledOnce()
  const body = scheduleApiMock.createPersonalSchedule.mock.calls[0]![0] as { startAt: string, endAt: string }
  return { startAt: body.startAt, endAt: body.endAt }
}

describe('週グリッド → フォーム保存: 秋の重複を跨ぐ範囲は実行日によらず 90分', () => {
  it.each([
    ['切替前（2026-10-30）', '2026-10-30T12:00:00Z'],
    ['切替当日の切替前（2026-11-01 03:00Z = EDT）', '2026-11-01T03:00:00Z'],
    ['切替当日の切替後（2026-11-01 07:00Z = EST）', '2026-11-01T07:00:00Z'],
    ['切替後（2026-11-03）', '2026-11-03T12:00:00Z'],
  ])('実行日が %s でも、保存される開始と終了が 90分になる', async (_label, now) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(now))

    const [gridStart, gridEnd] = await selectOnGrid()
    expect(gridStart).toBe('2026-11-01T01:30:00-04:00')
    expect(gridEnd).toBe('2026-11-01T02:00:00-05:00')

    const saved = await saveOnForm(gridStart, gridEnd)
    expect(saved.startAt).toBe('2026-11-01T01:30:00-04:00')
    expect(saved.endAt).toBe('2026-11-01T02:00:00-05:00')
    expect((dayjs(saved.endAt).valueOf() - dayjs(saved.startAt).valueOf()) / 60_000).toBe(90)
  })
})
