import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAttendanceResults } from './useAttendanceResults'

const mocks = vi.hoisted(() => ({ list: vi.fn(), api: vi.fn(), error: vi.fn(), timezone: 'Asia/Tokyo' }))
mockNuxtImport('useScheduleApi', () => () => ({ listSchedules: mocks.list }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref(mocks.timezone) }))
const summary = { attending: 1, partial: 2, absent: 3, undecided: 4, total: 10 }
function setup(limit = 10) {
  let result!: ReturnType<typeof useAttendanceResults>
  const scope = ref('scope-a')
  const wrapper = mount(
    defineComponent({
      setup() {
        result = useAttendanceResults('team', scope, limit)
        return () => null
      },
    }),
  )
  return {
    wrapper,
    scope,
    get result() {
      return result
    },
  }
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-24T03:00:00Z'))
  mocks.timezone = 'Asia/Tokyo'
  vi.clearAllMocks()
  mocks.list.mockResolvedValue({
    data: [
      {
        id: 1,
        content: { title: 'Practice', attendanceRequired: true },
        time: { startAt: '2026-10-24T12:00:00' },
      },
    ],
  })
  mocks.api.mockResolvedValue({ data: summary })
})
afterEach(() => vi.useRealTimers())
describe('出席確認取得（AC1/5/7/8/9）', () => {
  it('利用者TZの前後30日をoffset付きで取得する（AC6）', async () => {
    const test = setup()
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledWith('team', 'scope-a', {
      from: '2026-09-24T12:00:00+09:00',
      to: '2026-11-23T12:00:00+09:00',
    })
    test.wrapper.unmount()
  })
  it('夏時間を跨ぐ前後30日でも利用者TZのoffsetを付ける（AC6）', async () => {
    mocks.timezone = 'America/Los_Angeles'
    const test = setup()
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledWith('team', 'scope-a', {
      from: '2026-09-23T20:00:00-07:00',
      to: '2026-11-22T20:00:00-08:00',
    })
    test.wrapper.unmount()
  })
  it('0件なら成功した空状態とし統計を取得しない（AC5）', async () => {
    mocks.list.mockResolvedValue({ data: [] })
    const test = setup()
    await flushPromises()
    expect(test.result.error.value).toBeUndefined()
    expect(test.result.results.value).toEqual([])
    expect(mocks.api).not.toHaveBeenCalled()
    test.wrapper.unmount()
  })
  it('対象を絞った後の10件だけ統計を取得し最大11リクエストとする', async () => {
    mocks.list.mockResolvedValue({
      data: Array.from({ length: 30 }, (_, id) => ({
        id,
        content: { attendanceRequired: id >= 10 },
      })),
    })
    const test = setup()
    await flushPromises()
    expect(test.result.results.value).toHaveLength(10)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(mocks.api).toHaveBeenCalledTimes(10)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/schedules/10/stats')
    test.wrapper.unmount()
  })
  it('専用一覧は全件を保持し統計リクエストを送らない', async () => {
    const test = setup(0)
    await flushPromises()
    expect(test.result.schedules.value).toHaveLength(1)
    expect(mocks.api).not.toHaveBeenCalled()
    test.wrapper.unmount()
  })
  it('一覧失敗を空成功と区別し再試行で復旧する', async () => {
    const failure = new Error('network')
    mocks.list.mockRejectedValueOnce(failure)
    const test = setup()
    await flushPromises()
    expect(test.result.error.value).toBe(failure)
    await test.result.load()
    expect(test.result.error.value).toBeUndefined()
    expect(test.result.results.value[0]?.summary).toEqual(summary)
    test.wrapper.unmount()
  })
  it('統計の一部失敗でも別の成功行を維持し再試行できる', async () => {
    mocks.list.mockResolvedValue({
      data: [1, 2].map((id) => ({ id, content: { attendanceRequired: true } })),
    })
    mocks.api.mockRejectedValueOnce(new Error('stats failed'))
    const test = setup()
    await flushPromises()
    expect(test.result.results.value[0]?.error).toBeDefined()
    expect(test.result.results.value[1]?.summary).toEqual(summary)
    await test.result.load()
    expect(test.result.results.value.every((row) => row.summary)).toBe(true)
    test.wrapper.unmount()
  })
  it('scope切替後に遅れて返る前scope応答を混入させない', async () => {
    let resolveOld!: (value: { data: unknown[] }) => void
    mocks.list.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const test = setup()
    test.scope.value = 'scope-b'
    await flushPromises()
    resolveOld({ data: [{ id: 99, content: { attendanceRequired: true } }] })
    await flushPromises()
    expect(test.result.results.value.map((row) => row.schedule.id)).toEqual([1])
    test.wrapper.unmount()
  })
})
