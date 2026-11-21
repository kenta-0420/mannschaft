import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import RecruitmentListingsPage from './index.vue'

/**
 * 募集検索で絞り込み・ページ送りが連続したとき、古い応答が新しい表示を上書きしないことの試練
 * （CMP-260925-0903）。
 */

const { searchListings, listCategories } = vi.hoisted(() => ({ searchListings: vi.fn(), listCategories: vi.fn() }))
mockNuxtImport('useRecruitmentApi', () => () => ({ searchListings, listCategories }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

type Res = { data: Array<{ id: number }>, meta: { total: number, totalPages: number } }
function res(id: number): Res {
  const listing = {
    id, title: `listing-${id}`, startAt: '2026-12-01T10:00:00', location: null, status: 'OPEN',
    participationType: 'INDIVIDUAL', capacity: 5, confirmedCount: 0, paymentEnabled: false, price: null,
  }
  return { data: [listing], meta: { total: 1, totalPages: 1 } }
}

interface SearchVm {
  listings: Array<{ id: number }>
  loading: boolean
  searchFailed: boolean
  search: (page?: number) => Promise<void>
}

async function mountPage() {
  listCategories.mockResolvedValue({ data: [] })
  const wrapper = await mountSuspended(RecruitmentListingsPage)
  await flushPromises()
  return { wrapper, vm: wrapper.vm as unknown as SearchVm }
}

describe('募集検索 最後の要求の応答だけを反映する（CMP-260925-0903）', () => {
  beforeEach(() => {
    searchListings.mockReset()
    listCategories.mockReset()
  })

  it('遅い応答 A が速い応答 B の後に届いても、結果は B のまま', async () => {
    const a = deferred<Res>()
    const b = deferred<Res>()
    searchListings.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    void vm.search(1)
    b.resolve(res(2))
    await flushPromises()
    a.resolve(res(1))
    await flushPromises()

    expect(vm.listings.map(l => l.id)).toEqual([2])
    expect(vm.loading).toBe(false)
  })

  it('古い要求の失敗が、最新の成功表示をエラー状態に差し替えない', async () => {
    const a = deferred<Res>()
    const b = deferred<Res>()
    searchListings.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    void vm.search(1)
    b.resolve(res(2))
    await flushPromises()
    a.reject(new Error('stale failure'))
    await flushPromises()

    expect(vm.listings.map(l => l.id)).toEqual([2])
    expect(vm.searchFailed).toBe(false)
  })

  it('アンマウント後に届いた応答で状態を更新しない', async () => {
    const a = deferred<Res>()
    searchListings.mockReturnValueOnce(a.promise)
    const { wrapper, vm } = await mountPage()

    wrapper.unmount()
    a.resolve(res(1))
    await flushPromises()

    expect(vm.listings).toEqual([])
  })
})
