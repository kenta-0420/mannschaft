import { defineComponent, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { useFriendRequestSources } from './useFriendRequestSources'
import type { FriendRequestSourceTeamListResponse } from '~/types/friends'
const mocks = vi.hoisted(() => ({ list: vi.fn() }))
mockNuxtImport('useFriendTeamsApi', () => () => ({ listSourceTeams: mocks.list }))
const source = (id: number) => ({ teamId: id, teamSlug: 'team-' + id, teamName: 'Team ' + id })
const result = (ids: number[], total = ids.length): FriendRequestSourceTeamListResponse => ({ data: ids.map(source), pagination: { page: 0, size: 20, totalElements: total, totalPages: Math.ceil(total / 20), hasNext: total > 20 } })
async function setup() {
  const slug = ref('target-team')
  const open = ref(true)
  let state!: ReturnType<typeof useFriendRequestSources>
  const wrapper = await mountSuspended(defineComponent({ setup() { state = useFriendRequestSources(slug, open); return () => null } }))
  await flushPromises()
  return { state, slug, open, wrapper }
}
describe('FR-02/03/05 申請元候補', () => {
  beforeEach(() => mocks.list.mockReset())
  it('0件と取得失敗を区別する', async () => {
    mocks.list.mockResolvedValueOnce(result([])).mockRejectedValueOnce({ statusCode: 500 })
    const { state, wrapper } = await setup()
    expect(state.totalRecords.value).toBe(0)
    expect(state.error.value).toBeNull()
    await state.load()
    expect(state.error.value).toEqual({ statusCode: 500 })
    wrapper.unmount()
  })
  it('総数1だけ明示的に自動選択する', async () => {
    mocks.list.mockResolvedValueOnce(result([1]))
    const { state, wrapper } = await setup()
    expect(state.selected.value).toEqual(source(1))
    wrapper.unmount()
  })
  it('21件の2ページ目が1件でも未選択を維持し、跨ページの選択を保持する', async () => {
    mocks.list.mockResolvedValueOnce(result(Array.from({ length: 20 }, (_, i) => i + 1), 21)).mockResolvedValueOnce(result([21], 21))
    const { state, wrapper } = await setup()
    expect(state.selected.value).toBeNull()
    state.selected.value = source(4)
    state.page.value = 1
    await state.load()
    expect(state.selected.value).toEqual(source(4))
    expect(state.candidates.value).toEqual([source(21)])
    wrapper.unmount()
  })
  it('21→1の空成功だけpage0へ1回補正する', async () => {
    mocks.list.mockResolvedValueOnce(result([1], 21)).mockResolvedValueOnce(result([], 1)).mockResolvedValueOnce(result([1]))
    const { state, wrapper } = await setup()
    state.page.value = 1
    await state.load()
    expect(mocks.list.mock.calls.map(c => c[1].page)).toEqual([0, 1, 0])
    expect(state.page.value).toBe(0)
    expect(state.selected.value).toEqual(source(1))
    wrapper.unmount()
  })
  it('取得失敗では補正せず入力を保持する', async () => {
    mocks.list.mockResolvedValueOnce(result([1], 21)).mockRejectedValueOnce(new Error('network'))
    const { state, wrapper } = await setup()
    state.selected.value = source(1)
    state.page.value = 1
    await state.load()
    expect(state.page.value).toBe(1)
    expect(state.selected.value).toEqual(source(1))
    expect(mocks.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('相手変更・閉じて再度開く世代の遅延応答を捨てる', async () => {
    let resolveOld!: (v: FriendRequestSourceTeamListResponse) => void
    mocks.list.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValue(result([2]))
    const { state, slug, open, wrapper } = await setup()
    slug.value = 'new-target'
    await flushPromises()
    open.value = false
    await flushPromises()
    open.value = true
    await flushPromises()
    resolveOld(result([1]))
    await flushPromises()
    expect(state.selected.value).toEqual(source(2))
    expect(mocks.list.mock.calls.at(-1)?.[0]).toBe('new-target')
    wrapper.unmount()
  })
  it('同一相手でもpageが古い応答を捨てる', async () => {
    let resolveOld!: (v: FriendRequestSourceTeamListResponse) => void
    mocks.list.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce(result([21], 21))
    const { state, wrapper } = await setup()
    state.page.value = 1
    await state.load()
    resolveOld(result([1], 21))
    await flushPromises()
    expect(state.candidates.value).toEqual([source(21)])
    wrapper.unmount()
  })
})
