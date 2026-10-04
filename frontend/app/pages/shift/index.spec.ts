import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import Page from './index.vue'

const { createSchedule, listSchedules } = vi.hoisted(() => ({
  createSchedule: vi.fn(),
  listSchedules: vi.fn(),
}))
mockNuxtImport('useTeamStore', () => () => ({
  myTeams: [{ id: 12, role: 'ADMIN' }],
  fetchMyTeams: vi.fn(),
}))
mockNuxtImport('useShiftApi', () => () => ({ createSchedule, listSchedules }))
mockNuxtImport('useShiftRequestApi', () => () => ({ getRequestSummary: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn() }))

const Dialog = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', [slots.default?.(), slots.footer?.()]),
})
const DatePicker = defineComponent({
  name: 'DatePicker',
  props: { modelValue: Date, showTime: Boolean, hourFormat: String },
  emits: ['update:modelValue'],
  setup: () => () => h('input'),
})
beforeEach(() => {
  createSchedule.mockReset().mockResolvedValue({})
  listSchedules.mockReset().mockResolvedValue([])
})
interface PageVm {
  selectedTeamId: number
  createForm: { title: string; startDate: string; endDate: string; requestDeadline: Date | null }
  handleCreate: () => Promise<void>
}
async function mountPage() {
  const wrapper = await mountSuspended(Page, {
    global: {
      stubs: {
        Dialog,
        PageHeader: true,
        Button: true,
        Select: true,
        InputText: true,
        DatePicker,
        PageLoading: true,
        DashboardEmptyState: true,
        ShiftGuideModal: true,
        ShiftScheduleCard: true,
      },
    },
  })
  const vm = wrapper.vm as unknown as PageVm
  vm.selectedTeamId = 12
  Object.assign(vm.createForm, { title: '10月', startDate: '2026-10-05', endDate: '2026-10-11' })
  return { wrapper, vm }
}
describe('シフト新規作成のAPI契約', () => {
  it('入力した壁時計日時をLocalDateTimeとして送る', async () => {
    const { wrapper, vm } = await mountPage()
    const picker = wrapper.findComponent({ name: 'DatePicker' })
    expect(picker.exists()).toBe(true)
    expect(picker.props('showTime')).toBe(true)
    expect(picker.props('hourFormat')).toBe('24')
    const chosenDate = new Date(2026, 9, 4, 23, 30)
    picker.vm.$emit('update:modelValue', chosenDate)
    await wrapper.vm.$nextTick()
    expect(vm.createForm.requestDeadline).toEqual(chosenDate)
    await vm.handleCreate()
    expect(createSchedule).toHaveBeenCalledWith(
      '12',
      expect.objectContaining({ requestDeadline: '2026-10-04T23:30' }),
    )
    expect(vm.createForm.requestDeadline).toBeNull()
  })
  it('任意締切が未入力ならJSON payloadから省略される', async () => {
    const { vm } = await mountPage()
    vm.createForm.requestDeadline = null
    await vm.handleCreate()
    const body = JSON.parse(JSON.stringify(createSchedule.mock.calls[0]![1])) as Record<
      string,
      unknown
    >
    expect(body).not.toHaveProperty('requestDeadline')
  })
  it('作成CTAは6言語で解決できる既存キーを使う', () => {
    const source = readFileSync(resolve(process.cwd(), 'app/pages/shift/index.vue'), 'utf8')
    const key = source.match(/:label="t\('([^']+)'\)"\s+icon="pi pi-check"/)?.[1]
    expect(key).toBeDefined()
    for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de']) {
      const messages: Record<string, unknown> = Object.assign(
        {},
        ...['common', 'shift'].map(
          (file) =>
            JSON.parse(
              readFileSync(resolve(process.cwd(), 'app/locales', locale, `${file}.json`), 'utf8'),
            ) as Record<string, unknown>,
        ),
      )
      const value = key!
        .split('.')
        .reduce<unknown>(
          (node, part) =>
            node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
          messages,
        )
      expect(value).toBeTypeOf('string')
      expect(value).not.toBe(key)
      expect(value).not.toBe('')
    }
  })
})
