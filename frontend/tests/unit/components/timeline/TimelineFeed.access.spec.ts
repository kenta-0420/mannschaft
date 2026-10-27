import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TimelineFeed from '~/components/timeline/TimelineFeed.vue'

const { getFeed, showError } = vi.hoisted(() => ({ getFeed: vi.fn(), showError: vi.fn() }))
mockNuxtImport('useTimelineApi', () => () => ({ getFeed }))
mockNuxtImport('useNotification', () => () => ({ showError, showSuccess: vi.fn() }))
mockNuxtImport('useUndoToast', () => () => ({ showUndoToast: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTimelineMutes', () => () => ({ mutes: ref([]), muteCount: ref(0), loading: ref(false), loadMutes: vi.fn(), mute: vi.fn(), unmute: vi.fn() }))

const emptyFeed = () => ({ data: { pinned: [], posts: [] }, meta: { nextCursor: null, hasNext: false } })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const wrappers: Array<Awaited<ReturnType<typeof mountSuspended>>> = []
async function mountFeed() {
  const wrapper = await mountSuspended(TimelineFeed, { props: { scopeType: 'TEAM', scopeId: 'scope-a' }, global: { stubs: { TimelinePostCard: true } } })
  wrappers.push(wrapper)
  return wrapper
}

describe('TimelineFeed の読取結果と拒否表示', () => {
  beforeEach(() => { vi.clearAllMocks(); getFeed.mockResolvedValue(emptyFeed()) })
  afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

  it('初回 pending は許可を通知せず、空200も読取成立を通知する', async () => {
    const request = deferred<ReturnType<typeof emptyFeed>>()
    getFeed.mockReturnValueOnce(request.promise)
    const wrapper = await mountFeed()
    expect(wrapper.emitted('read-access')).toBeUndefined()
    request.resolve(emptyFeed()); await flushPromises()
    expect(wrapper.emitted('read-access')).toEqual([[true, 'TEAM', 'scope-a']])
    expect(wrapper.text()).toContain('まだ投稿がありません')
  })

  it.each([403, 404])('%i は空成功にせず永続拒否表示と false 通知にする', async statusCode => {
    getFeed.mockRejectedValueOnce({ statusCode })
    const wrapper = await mountFeed(); await flushPromises()
    expect(wrapper.emitted('read-access')).toEqual([[false, 'TEAM', 'scope-a']])
    expect(wrapper.find('[data-testid="timeline-access-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain(statusCode === 403 ? 'loadErrorState.states.forbidden.title' : 'loadErrorState.states.notFoundOrForbidden.title')
    expect(wrapper.text()).not.toContain('まだ投稿がありません')
  })

  it('成立後の transient500 は読取成立を取り消さない', async () => {
    const wrapper = await mountFeed(); await flushPromises()
    getFeed.mockRejectedValueOnce({ statusCode: 500 })
    ;(wrapper.vm as unknown as { refresh: () => void }).refresh(); await flushPromises()
    expect(wrapper.emitted('read-access')).toEqual([[true, 'TEAM', 'scope-a']])
    expect(showError).toHaveBeenCalled()
  })

  it('同scopeの後続403を古い200が上書きしない', async () => {
    const old = deferred<ReturnType<typeof emptyFeed>>()
    getFeed.mockReturnValueOnce(old.promise)
    const wrapper = await mountFeed()
    getFeed.mockRejectedValueOnce({ statusCode: 403 })
    ;(wrapper.vm as unknown as { refresh: () => void }).refresh(); await flushPromises()
    old.resolve(emptyFeed()); await flushPromises()
    expect(wrapper.emitted('read-access')).toEqual([[false, 'TEAM', 'scope-a']])
    expect(wrapper.find('[data-testid="timeline-access-error"]').exists()).toBe(true)
  })

  it('取得済み投稿と追加cursorを後続403で消す', async () => {
    getFeed.mockResolvedValueOnce({ data: { pinned: [{ id: 1 }], posts: [{ id: 2 }] }, meta: { nextCursor: 2, hasNext: true } })
    const wrapper = await mountFeed(); await flushPromises()
    getFeed.mockRejectedValueOnce({ statusCode: 403 })
    ;(wrapper.vm as unknown as { refresh: () => void }).refresh(); await flushPromises()
    expect(wrapper.findAllComponents({ name: 'TimelinePostCard' })).toHaveLength(0)
    expect(wrapper.find('[data-testid="timeline-load-more-target"]').exists()).toBe(false)
    expect(wrapper.emitted('read-access')?.at(-1)).toEqual([false, 'TEAM', 'scope-a'])
  })
})
