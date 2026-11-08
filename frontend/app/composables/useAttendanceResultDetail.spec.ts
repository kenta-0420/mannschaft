import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAttendanceResultDetail } from './useAttendanceResultDetail'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  list: vi.fn(),
  attendances: vi.fn(),
  api: vi.fn(),
  error: vi.fn(),
}))
mockNuxtImport('useScheduleApi', () => () => ({
  getSchedule: mocks.get,
  listSchedules: mocks.list,
  getAttendances: mocks.attendances,
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useDatetime', () => () => ({
  userTimezone: ref('Asia/Tokyo'),
  buildDayStartStr: (value: string) => `${value}T00:00:00+09:00`,
  buildDayEndStr: (value: string) => `${value}T23:59:59+09:00`,
}))
function setup(id = '1') {
  let result!: ReturnType<typeof useAttendanceResultDetail>
  const scheduleId = ref(id)
  const wrapper = mount(
    defineComponent({
      setup() {
        result = useAttendanceResultDetail('team', 'scope-a', scheduleId)
        return () => null
      },
    }),
  )
  return {
    wrapper,
    scheduleId,
    get result() {
      return result
    },
  }
}
const schedule = {
  id: 1,
  content: { title: 'Private practice', attendanceRequired: true },
  time: { startAt: '2026-10-24T00:00:00', allDay: true },
  targetMode: 'SELECTED_MEMBERS',
  targets: [{ userId: 7, displayName: 'Member' }],
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockResolvedValue({ data: schedule })
  mocks.list.mockResolvedValue({ data: [schedule] })
  mocks.attendances.mockResolvedValue({
    data: [{ id: 1, userId: 7, status: 'ABSENT', comment: 'Private reason' }],
  })
  mocks.api.mockResolvedValue({
    data: { attending: 0, partial: 0, absent: 1, undecided: 0, total: 1 },
  })
})
describe('出席確認詳細の公開境界（AC4/6/8/9/10）', () => {
  it('同scopeの一覧照合後にだけ理由と人数を取得する', async () => {
    const test = setup()
    await flushPromises()
    expect(test.result.schedule.value?.content?.title).toBe('Private practice')
    expect(test.result.attendances.value[0]?.comment).toBe('Private reason')
    expect(mocks.list).toHaveBeenCalledWith('team', 'scope-a', {
      from: '2026-10-23T00:00:00+09:00',
      to: '2026-10-25T23:59:59+09:00',
    })
    expect(mocks.list.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.attendances.mock.invocationCallOrder[0]!,
    )
    test.wrapper.unmount()
  })
  it('別scopeのIDはタイトル・人数・理由を表示せず理由APIも呼ばない', async () => {
    mocks.list.mockResolvedValue({ data: [] })
    const test = setup()
    await flushPromises()
    expect(test.result.error.value).toEqual({ statusCode: 404 })
    expect(test.result.schedule.value).toBeUndefined()
    expect(test.result.summary.value).toBeUndefined()
    expect(test.result.attendances.value).toEqual([])
    expect(mocks.attendances).not.toHaveBeenCalled()
    test.wrapper.unmount()
  })
  it.each(['invalid', '0', '-1', '9007199254740992'])('不正ID %s をAPIへ送らない', async (id) => {
    const test = setup(id)
    await flushPromises()
    expect(mocks.get).not.toHaveBeenCalled()
    expect(test.result.error.value).toEqual({ statusCode: 404 })
    test.wrapper.unmount()
  })
  it('照合取得失敗は空成功にせず全取得を再試行する', async () => {
    mocks.list.mockRejectedValueOnce(new Error('network'))
    const test = setup()
    await flushPromises()
    expect(test.result.error.value).toBeDefined()
    expect(test.result.schedule.value).toBeUndefined()
    await test.result.load()
    expect(test.result.error.value).toBeUndefined()
    expect(test.result.attendances.value).toHaveLength(1)
    test.wrapper.unmount()
  })
  it('ID切替後の遅い理由応答を混入させない', async () => {
    let resolveOld!: (value: { data: unknown[] }) => void
    mocks.attendances.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const test = setup()
    await flushPromises()
    mocks.get.mockResolvedValue({
      data: { ...schedule, id: 2, content: { title: 'Second', attendanceRequired: true } },
    })
    mocks.list.mockResolvedValue({ data: [{ ...schedule, id: 2 }] })
    test.scheduleId.value = '2'
    await flushPromises()
    resolveOld({ data: [{ comment: 'Old secret' }] })
    await flushPromises()
    expect(test.result.schedule.value?.id).toBe(2)
    expect(test.result.attendances.value.some((item) => item.comment === 'Old secret')).toBe(false)
    test.wrapper.unmount()
  })
})
