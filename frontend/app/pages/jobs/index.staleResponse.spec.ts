import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import JobsPage from './index.vue'

/**
 * 求人一覧（Worker 向け）でチーム切替・ページ送りが重なったとき、
 * 古い応答が新しい表示を上書きしないことの試練（CMP-260925-0903）。
 */

const { searchJobs } = vi.hoisted(() => ({ searchJobs: vi.fn() }))
mockNuxtImport('useJobPostingApi', () => () => ({ searchJobs }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

type Page = { data: Array<{ id: number }>, meta: { total: number, page: number, size: number, totalPages: number } }
function page(id: number): Page {
  return { data: [{ id }], meta: { total: 1, page: 0, size: 20, totalPages: 1 } }
}

interface JobsVm {
  jobs: Array<{ id: number }>
  loading: boolean
  loadFailed: boolean
  onPageChange: (e: { page: number }) => void
}

async function mountPage() {
  const store = useTeamStore()
  store.$patch({ myTeams: [{ id: 1, name: 'A' }] as never })
  vi.spyOn(store, 'fetchMyTeams').mockResolvedValue()
  const wrapper = await mountSuspended(JobsPage, { global: { stubs: { JobsGuideModal: true, JobPostingCard: true } } })
  await flushPromises()
  return { wrapper, vm: wrapper.vm as unknown as JobsVm }
}

describe('求人一覧 最後の要求の応答だけを反映する（CMP-260925-0903）', () => {
  beforeEach(() => searchJobs.mockReset())

  it('遅い応答 A が速い応答 B の後に届いても、一覧は B のまま', async () => {
    const a = deferred<Page>()
    const b = deferred<Page>()
    searchJobs.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    vm.onPageChange({ page: 1 })
    b.resolve(page(2))
    await flushPromises()
    a.resolve(page(1))
    await flushPromises()

    expect(vm.jobs.map(j => j.id)).toEqual([2])
    expect(vm.loading).toBe(false)
  })

  it('古い要求の失敗が、最新の成功表示をエラー状態に差し替えない', async () => {
    const a = deferred<Page>()
    const b = deferred<Page>()
    searchJobs.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    vm.onPageChange({ page: 1 })
    b.resolve(page(2))
    await flushPromises()
    a.reject(new Error('stale failure'))
    await flushPromises()

    expect(vm.jobs.map(j => j.id)).toEqual([2])
    expect(vm.loadFailed).toBe(false)
  })

  it('アンマウント後に届いた応答で状態を更新しない', async () => {
    const a = deferred<Page>()
    searchJobs.mockReturnValueOnce(a.promise)
    const { wrapper, vm } = await mountPage()

    wrapper.unmount()
    a.resolve(page(1))
    await flushPromises()

    expect(vm.jobs).toEqual([])
  })
})
