import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ReviewPage from './monitoring-visits-review.vue'

/**
 * 在留資格モニタリングの訪問履歴検索で、検索の連続実行・モード切替の後に古い応答が届いても
 * 新しい表示を上書きしないことの試練（CMP-260925-0903）。
 */

const { listVisitsByCommittee, listVisitsByResident } = vi.hoisted(() => ({
  listVisitsByCommittee: vi.fn(), listVisitsByResident: vi.fn(),
}))
mockNuxtImport('useMonitoringVisitApi', () => () => ({ listVisitsByCommittee, listVisitsByResident }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-a' } }))

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

type Res = { data: Array<{ id: number }> }

interface ReviewVm {
  visits: Array<{ id: number }>
  loading: boolean
  loadFailed: boolean
  searched: boolean
  committeeIdInput: string
  handleSearch: () => Promise<void>
  handleFilterModeChange: (mode: 'committee' | 'resident') => void
}

async function mountPage() {
  const wrapper = await mountSuspended(ReviewPage)
  return { wrapper, vm: wrapper.vm as unknown as ReviewVm }
}

describe('訪問履歴レビュー 最後の要求の応答だけを反映する（CMP-260925-0903）', () => {
  beforeEach(() => {
    listVisitsByCommittee.mockReset()
    listVisitsByResident.mockReset()
  })

  it('遅い応答 A が速い応答 B の後に届いても、一覧は B のまま', async () => {
    const a = deferred<Res>()
    const b = deferred<Res>()
    listVisitsByCommittee.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    vm.committeeIdInput = '1'
    void vm.handleSearch()
    vm.committeeIdInput = '2'
    void vm.handleSearch()
    b.resolve({ data: [{ id: 2 }] })
    await flushPromises()
    a.resolve({ data: [{ id: 1 }] })
    await flushPromises()

    expect(vm.visits.map(v => v.id)).toEqual([2])
    expect(vm.loading).toBe(false)
  })

  it('古い要求の失敗が、最新の成功表示をエラー状態に差し替えない', async () => {
    const a = deferred<Res>()
    const b = deferred<Res>()
    listVisitsByCommittee.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { vm } = await mountPage()

    vm.committeeIdInput = '1'
    void vm.handleSearch()
    vm.committeeIdInput = '2'
    void vm.handleSearch()
    b.resolve({ data: [{ id: 2 }] })
    await flushPromises()
    a.reject(new Error('stale failure'))
    await flushPromises()

    expect(vm.visits.map(v => v.id)).toEqual([2])
    expect(vm.loadFailed).toBe(false)
  })

  it('検索中にモードを切り替えたら、切替前の応答は一覧に出ない', async () => {
    const a = deferred<Res>()
    listVisitsByCommittee.mockReturnValueOnce(a.promise)
    const { vm } = await mountPage()

    vm.committeeIdInput = '1'
    void vm.handleSearch()
    vm.handleFilterModeChange('resident')
    a.resolve({ data: [{ id: 1 }] })
    await flushPromises()

    expect(vm.visits).toEqual([])
    expect(vm.searched).toBe(false)
    expect(vm.loading).toBe(false)
  })

  it('アンマウント後に届いた応答で状態を更新しない', async () => {
    const a = deferred<Res>()
    listVisitsByCommittee.mockReturnValueOnce(a.promise)
    const { wrapper, vm } = await mountPage()

    vm.committeeIdInput = '1'
    void vm.handleSearch()
    wrapper.unmount()
    a.resolve({ data: [{ id: 1 }] })
    await flushPromises()

    expect(vm.visits).toEqual([])
  })
})
