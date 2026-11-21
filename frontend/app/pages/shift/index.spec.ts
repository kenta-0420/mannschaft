import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftIndexPage from './index.vue'

const mocks = vi.hoisted(() => ({
  listSchedules: vi.fn(),
  createSchedule: vi.fn(),
  getRequestSummary: vi.fn(),
  handleError: vi.fn(),
  success: vi.fn(),
  fetchMyTeams: vi.fn(),
  teams: [{ id: 7, name: 'テストチーム', role: 'ADMIN' }],
}))
mockNuxtImport('useTeamStore', () => () => ({
  get myTeams() {
    return mocks.teams
  },
  fetchMyTeams: mocks.fetchMyTeams,
}))
mockNuxtImport('useShiftApi', () => () => ({
  listSchedules: mocks.listSchedules,
  createSchedule: mocks.createSchedule,
}))
mockNuxtImport('useShiftRequestApi', () => () => ({ getRequestSummary: mocks.getRequestSummary }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleError }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

// 実機では「無効化が描画される前に2回目のクリックが届く」ため、スタブは disabled を無視して
// クリックをそのまま通し、ハンドラ側の再入防止だけを検証する。
async function mountPage() {
  const wrapper = await mountSuspended(ShiftIndexPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        DashboardEmptyState: true,
        ShiftScheduleCard: true,
        ShiftGuideModal: true,
        Select: true,
        InputText: true,
        Dialog: { template: '<section><slot /><slot name="footer" /></section>' },
        Button: {
          props: ['label', 'loading'],
          emits: ['click'],
          template: '<button :data-loading="loading" @click="$emit(\'click\')">{{ label }}</button>',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

function createButton(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  const button = wrapper.findAll('button').find((b) => b.text() === 'button.create')
  if (!button) throw new Error('作成ボタンが見つからない')
  return button
}

describe('シフト表の作成（連打対策）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.fetchMyTeams.mockResolvedValue(undefined)
    mocks.listSchedules.mockResolvedValue([])
    mocks.getRequestSummary.mockResolvedValue({})
  })

  async function prepare() {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as {
      createForm: { title: string; startDate: string; endDate: string }
    }
    vm.createForm.title = '10月シフト'
    vm.createForm.startDate = '2026-10-01'
    vm.createForm.endDate = '2026-10-31'
    await flushPromises()
    return wrapper
  }

  it('作成ボタンを続けて2回押しても API は1回しか呼ばれない', async () => {
    const pending = deferred<unknown>()
    mocks.createSchedule.mockReturnValue(pending.promise)
    const wrapper = await prepare()

    const button = createButton(wrapper)
    await button.trigger('click')
    await button.trigger('click')

    expect(mocks.createSchedule).toHaveBeenCalledTimes(1)
    pending.resolve({})
    await flushPromises()
    expect(mocks.createSchedule).toHaveBeenCalledTimes(1)
  })

  it('作成が失敗したら再入防止が解除され、もう一度作成できる', async () => {
    mocks.createSchedule.mockRejectedValueOnce(new Error('500'))
    mocks.createSchedule.mockResolvedValueOnce({})
    const wrapper = await prepare()

    await createButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.handleError).toHaveBeenCalledTimes(1)

    await createButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.createSchedule).toHaveBeenCalledTimes(2)
  })
})
