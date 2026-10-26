import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import Menu from 'primevue/menu'
import TimelineFeed from '~/components/timeline/TimelineFeed.vue'
import type { TimelinePostResponse, TimelineMute } from '~/types/timeline'

/**
 * CMP-058 タイムラインのミュート（非表示）導線 — TimelineFeed.vue ユニットテスト。
 *
 * 検証観点:
 *   UNIT-TL-MUTE-001: ミュート0件のときは「非表示中」チップを出さない（段階開示）
 *   UNIT-TL-MUTE-002: ミュートがあるときはチップを件数付きで出す
 *   UNIT-TL-MUTE-003: 個人フィード以外ではカードにミュート項目を出さない
 *   UNIT-TL-MUTE-004: ミュート実行で正しい引数の API 呼び出しが起き、投稿が即座に消える
 *   UNIT-TL-MUTE-005: 「元に戻す」で解除 API（クエリパラメータ形式）が呼ばれる
 *   UNIT-TL-MUTE-006: 200件上限エラー（TIMELINE_017）がユーザーに見える形で扱われ、投稿が復帰する
 */

const getMyTimeline = vi.fn()
const getFeed = vi.fn()
const getMutes = vi.fn()
const addMute = vi.fn()
const removeMute = vi.fn()
const handleApiError = vi.fn()
const showUndoToast = vi.fn()
const showSuccess = vi.fn()
const showError = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
  te: () => true,
  locale: { value: 'ja' },
}))

mockNuxtImport('useNotification', () => () => ({
  showSuccess,
  showError,
  success: showSuccess,
  error: showError,
  info: vi.fn(),
  warn: vi.fn(),
  showInfo: vi.fn(),
  showWarn: vi.fn(),
}))

mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError,
  handleError: handleApiError,
  resolveMessage: (code: string) => code,
  getFieldErrors: () => ({}),
}))

mockNuxtImport('useUndoToast', () => () => ({ showUndoToast }))

mockNuxtImport('useTimelineApi', () => () => ({
  getFeed,
  getMyTimeline,
  getMutes,
  addMute,
  removeMute,
  addBookmark: vi.fn(),
  removeBookmark: vi.fn(),
  pinPost: vi.fn(),
  deletePost: vi.fn(),
  repost: vi.fn(),
  addReaction: vi.fn(),
  removeReaction: vi.fn(),
  getReplies: vi
    .fn()
    .mockResolvedValue({ data: { posts: [] }, meta: { nextCursor: null, hasNext: false } }),
  createReply: vi.fn(),
}))

// ─── フィクスチャ ─────────────────────────────────
function makePost(
  id: number,
  scopeType: 'TEAM' | 'ORGANIZATION',
  scopeId: string,
  name: string,
): TimelinePostResponse {
  return {
    id,
    scope: { scopeType, scopeId, name, slug: `slug-${scopeId}` },
    author: { userId: 1, socialProfileId: null, postedAsType: null, postedAsId: null },
    content: {
      content: `投稿${id}`,
      parentId: null,
      repostOfId: null,
      status: 'PUBLISHED',
      scheduledAt: null,
      isPinned: false,
    },
    stats: { repostCount: 0, reactionCount: 0, replyCount: 0, attachmentCount: 0, editCount: 0 },
    audit: { createdAt: '2026-08-19T10:00:00', updatedAt: '2026-08-19T10:00:00' },
    user: { id: 1, displayName: '太郎', avatarUrl: null },
    postedAs: null,
    isBookmarked: false,
    isEdited: false,
    isTruncated: false,
    mitayo: false,
    mitayoCount: 0,
    attachments: [],
    repostOf: null,
    poll: null,
  }
}

function makeMute(id: number, mutedType: 'TEAM' | 'ORGANIZATION', mutedId: number): TimelineMute {
  return { id, userId: 1, mutedType, mutedId, createdAt: '2026-08-19T10:00:00' }
}

/** 毎回新しい配列・オブジェクトを返す（前のテストの楽観更新を持ち越さない）。 */
function feedResponse() {
  return {
    data: {
      pinned: [],
      posts: [makePost(1, 'ORGANIZATION', '5', 'さくら学園'), makePost(2, 'TEAM', '7', '一軍')],
    },
    meta: { nextCursor: null, limit: 20, hasNext: false },
  }
}

/** 指定カードのケバブメニュー項目（Menu の model）を取り出す。 */
function menuModelAt(
  wrapper: Awaited<ReturnType<typeof mountSuspended>>,
  index: number,
): Array<{ label: string; command: () => void }> {
  const menus = wrapper.findAllComponents(Menu)
  return menus[index]!.props('model') as Array<{ label: string; command: () => void }>
}

describe('TimelineFeed.vue — ミュート導線', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMyTimeline.mockImplementation(async () => feedResponse())
    getFeed.mockImplementation(async () => feedResponse())
    getMutes.mockResolvedValue({ data: [] })
    addMute.mockImplementation(
      async (p: { mutedType: 'TEAM' | 'ORGANIZATION'; mutedId: number }) => ({
        data: makeMute(99, p.mutedType, p.mutedId),
      }),
    )
    removeMute.mockResolvedValue(undefined)
  })

  it('個人集約モードは scope フィードでなく /timeline/my を取得する', async () => {
    await mountSuspended(TimelineFeed, { props: { myFeed: true } })

    expect(getMyTimeline).toHaveBeenCalledTimes(1)
    expect(getFeed).not.toHaveBeenCalled()
  })

  it('UNIT-TL-MUTE-001: ミュート0件のときは「非表示中」チップを出さない', async () => {
    const wrapper = await mountSuspended(TimelineFeed, { props: { myFeed: true } })
    expect(getMutes).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="timeline-muted-chip"]').exists()).toBe(false)
  })

  it('UNIT-TL-MUTE-002: ミュートがあるときはチップを件数付きで出す', async () => {
    getMutes.mockResolvedValue({
      data: [makeMute(1, 'ORGANIZATION', 5), makeMute(2, 'TEAM', 7)],
    })
    const wrapper = await mountSuspended(TimelineFeed, { props: { myFeed: true } })
    const chip = wrapper.find('[data-testid="timeline-muted-chip"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('"count":2')
  })

  it('UNIT-TL-MUTE-003: 個人フィード以外ではカードにミュート項目を出さない', async () => {
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'ORGANIZATION', scopeId: '5' },
    })
    const items = menuModelAt(wrapper, 0)
    expect(items.some((i) => i.label.startsWith('timeline.mute.menuLabel'))).toBe(false)
  })

  it('UNIT-TL-MUTE-004: ミュート実行で正しい引数の API 呼び出しが起き、投稿が即座に消える', async () => {
    const wrapper = await mountSuspended(TimelineFeed, { props: { myFeed: true } })
    expect(wrapper.findAll('[data-testid="team-timeline-post"]')).toHaveLength(2)

    const items = menuModelAt(wrapper, 0)
    const muteItem = items.find((i) => i.label === 'timeline.mute.menuLabel.ORGANIZATION')
    expect(muteItem).toBeTruthy()

    muteItem!.command()
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    // 確認ダイアログを挟まず即実行される（摩擦ゼロ）
    expect(addMute).toHaveBeenCalledTimes(1)
    expect(addMute).toHaveBeenCalledWith({ mutedType: 'ORGANIZATION', mutedId: 5 })
    // 楽観更新: 該当スコープの投稿だけがリストから消える
    expect(wrapper.findAll('[data-testid="team-timeline-post"]')).toHaveLength(1)
    // 「元に戻す」付きトーストが出る
    expect(showUndoToast).toHaveBeenCalledTimes(1)
  })

  it('UNIT-TL-MUTE-005: 「元に戻す」で解除 API が呼ばれる', async () => {
    const wrapper = await mountSuspended(TimelineFeed, { props: { myFeed: true } })
    const muteItem = menuModelAt(wrapper, 0).find(
      (i) => i.label === 'timeline.mute.menuLabel.ORGANIZATION',
    )
    muteItem!.command()
    await new Promise((r) => setTimeout(r, 0))

    const toastArg = showUndoToast.mock.calls[0]![0] as {
      undoLabel: string
      onUndo: () => Promise<void>
    }
    expect(toastArg.undoLabel).toBe('timeline.mute.undo')

    await toastArg.onUndo()
    expect(removeMute).toHaveBeenCalledWith({ mutedType: 'ORGANIZATION', mutedId: 5 })
  })

  it('UNIT-TL-MUTE-006: 200件上限（TIMELINE_017）はユーザーに見える形で扱われ、投稿が復帰する', async () => {
    const limitError = { data: { error: { code: 'TIMELINE_017', message: 'MAX_MUTES_EXCEEDED' } } }
    addMute.mockRejectedValue(limitError)

    const wrapper = await mountSuspended(TimelineFeed, { props: { myFeed: true } })
    const muteItem = menuModelAt(wrapper, 0).find(
      (i) => i.label === 'timeline.mute.menuLabel.ORGANIZATION',
    )
    muteItem!.command()
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    // 握りつぶさず、ErrorCode 付きで共通ハンドラへ渡す（文言は i18n error.TIMELINE_017）
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(handleApiError.mock.calls[0]![0]).toBe(limitError)
    // 「元に戻す」トーストは出さない（ミュートは成立していないため）
    expect(showUndoToast).not.toHaveBeenCalled()

    // 楽観更新を巻き戻し、消した投稿を戻す（状態を偽らない）
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('[data-testid="team-timeline-post"]')).toHaveLength(2)
  })
})

describe('TimelineFeed.vue — 追加読み込み', () => {
  let onIntersection: IntersectionObserverCallback
  const observe = vi.fn()
  const unobserve = vi.fn()
  const disconnect = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback) {
          onIntersection = callback
        }

        observe = observe
        unobserve = unobserve
        disconnect = disconnect
      },
    )
    getMutes.mockResolvedValue({ data: [] })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('末尾が見えたらカーソルで次ページを一度だけ取得し、終端で監視を解除する', async () => {
    getFeed
      .mockResolvedValueOnce({
        data: {
          pinned: [],
          posts: [makePost(3, 'TEAM', '7', '一軍'), makePost(2, 'TEAM', '7', '一軍')],
        },
        meta: { nextCursor: 2, limit: 2, hasNext: true },
      })
      .mockResolvedValueOnce({
        data: { pinned: [], posts: [makePost(1, 'TEAM', '7', '一軍')] },
        meta: { nextCursor: null, limit: 2, hasNext: false },
      })

    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
    })
    await vi.waitFor(() => expect(observe).toHaveBeenCalledOnce())
    const target = wrapper.find('[data-testid="timeline-load-more-target"]')
    expect(target.exists()).toBe(true)
    expect(observe).toHaveBeenCalledWith(target.element)

    onIntersection(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    onIntersection(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    await vi.waitFor(() => expect(getFeed).toHaveBeenCalledTimes(2))
    expect(getFeed).toHaveBeenLastCalledWith({ scopeType: 'TEAM', scopeId: '7', cursor: 2 })
    await vi.waitFor(() =>
      expect(wrapper.findAll('[data-testid="team-timeline-post"]')).toHaveLength(3),
    )
    expect(wrapper.find('[data-testid="timeline-load-more-target"]').exists()).toBe(false)
    expect(disconnect).toHaveBeenCalled()
  })

  it('表示上限を指定したウィジェットでは監視も追加取得も行わない', async () => {
    getFeed.mockResolvedValue({
      data: {
        pinned: [],
        posts: [makePost(3, 'TEAM', '7', '一軍'), makePost(2, 'TEAM', '7', '一軍')],
      },
      meta: { nextCursor: 2, limit: 2, hasNext: true },
    })

    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7', limit: 1 },
    })
    expect(wrapper.findAll('[data-testid="team-timeline-post"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="timeline-load-more-target"]').exists()).toBe(false)
    expect(observe).not.toHaveBeenCalled()
    expect(getFeed).toHaveBeenCalledTimes(1)
    expect(getFeed).toHaveBeenCalledWith({ scopeType: 'TEAM', scopeId: '7', cursor: undefined, limit: 1 })
  })

  it('追加取得に失敗したら自動再試行せず、ボタンで再試行できる', async () => {
    getFeed
      .mockResolvedValueOnce({
        data: { pinned: [], posts: [makePost(3, 'TEAM', '7', '一軍')] },
        meta: { nextCursor: 3, limit: 1, hasNext: true },
      })
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce({
        data: { pinned: [], posts: [makePost(2, 'TEAM', '7', '一軍')] },
        meta: { nextCursor: null, limit: 1, hasNext: false },
      })

    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
    })
    await vi.waitFor(() => expect(observe).toHaveBeenCalledOnce())
    onIntersection(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="timeline-load-more-target"] button').exists()).toBe(true),
    )
    expect(getFeed).toHaveBeenCalledTimes(2)

    onIntersection(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(getFeed).toHaveBeenCalledTimes(2)

    await wrapper.find('[data-testid="timeline-load-more-target"] button').trigger('click')
    await vi.waitFor(() => expect(getFeed).toHaveBeenCalledTimes(3))
  })
})

describe('TimelineFeed.vue — 手動更新', () => {
  let onIntersection: IntersectionObserverCallback

  function intersect() {
    onIntersection(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    )
  }

  function response(ids: number[], pinnedIds: number[] = [], cursor: number | null = null) {
    return {
      data: {
        pinned: pinnedIds.map((id) => makePost(id, 'TEAM', '7', '一軍')),
        posts: ids.map((id) => makePost(id, 'TEAM', '7', '一軍')),
      },
      meta: { nextCursor: cursor, limit: 20, hasNext: cursor !== null },
    }
  }

  function deferredResponse() {
    let resolve!: (value: ReturnType<typeof response>) => void
    const promise = new Promise<ReturnType<typeof response>>((done) => {
      resolve = done
    })
    return { promise, resolve }
  }

  const stubs = {
    TimelinePostCard: {
      props: ['post'],
      template: '<div data-testid="refresh-post">{{ post.id }}</div>',
    },
  }

  beforeEach(() => {
    vi.clearAllMocks()
    getFeed.mockReset().mockResolvedValue(response([2, 1], [10]))
    getMyTimeline.mockReset().mockResolvedValue(response([2, 1]))
    getMutes.mockResolvedValue({ data: [] })
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback) {
          onIntersection = callback
        }
        observe() {}
        disconnect() {}
      },
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each([
    { scopeType: 'TEAM' as const, scopeId: '7' },
    { scopeType: 'ORGANIZATION' as const, scopeId: '5' },
    { scopeType: 'VILLAGE' as const, scopeId: 'village-uuid' },
    { scopeType: 'PUBLIC' as const, scopeId: undefined },
  ])(
    'AC-1/2: $scopeType の上部ボタンで同じscopeの先頭を取得し投稿とピンを置換する',
    async (props) => {
      const wrapper = await mountSuspended(TimelineFeed, { props, global: { stubs } })
      const button = wrapper.get('[data-testid="timeline-refresh"]')
      expect(button.text()).toContain('timeline.refresh')
      expect(button.attributes('type')).toBe('button')
      expect(button.classes()).toContain('min-h-11')
      expect(button.classes()).toContain('min-w-11')
      getFeed.mockResolvedValueOnce(response([4, 3], [11]))

      await button.trigger('click')

      await vi.waitFor(() =>
        expect(wrapper.findAll('[data-testid="refresh-post"]').map((p) => p.text())).toEqual([
          '11',
          '4',
          '3',
        ]),
      )
      expect(getFeed).toHaveBeenLastCalledWith({ ...props, cursor: undefined, limit: undefined })
      expect(getMyTimeline).not.toHaveBeenCalled()
    },
  )

  it('AC-2/4: 個人ウィジェットも先頭を再取得し上限を維持、空の結果でも再更新できる', async () => {
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { myFeed: true, limit: 1 },
      global: { stubs },
    })
    getMyTimeline.mockResolvedValueOnce(response([4, 3], [], 3))

    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.findAll('[data-testid="refresh-post"]').map((p) => p.text())).toEqual(['4']),
    )
    expect(getMyTimeline).toHaveBeenNthCalledWith(1, undefined, 1)
    expect(getMyTimeline).toHaveBeenLastCalledWith(undefined, 1)
    expect(getFeed).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="timeline-load-more-target"]').exists()).toBe(false)
    getMyTimeline.mockResolvedValueOnce(response([]))
    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid="refresh-post"]')).toHaveLength(0))
    expect(wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled')).toBeUndefined()
  })

  it('AC-3: 初期読み込み中は手動更新を無効にする', async () => {
    const pending = deferredResponse()
    getFeed.mockReturnValueOnce(pending.promise)
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
      global: { stubs },
    })

    expect(wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')
    expect(getFeed).toHaveBeenCalledTimes(1)
    pending.resolve(response([1]))
    await vi.waitFor(() =>
      expect(
        wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled'),
      ).toBeUndefined(),
    )
  })

  it('AC-3/4: 更新中の連打と末尾交差を抑止し、新しいカーソルから追加取得する', async () => {
    getFeed.mockResolvedValueOnce(response([2, 1], [], 1))
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
      global: { stubs },
    })
    const pending = deferredResponse()
    getFeed.mockReturnValueOnce(pending.promise)

    // Vueのdisabled反映前の連打もhandlerで抑止する。
    const button = wrapper.get('[data-testid="timeline-refresh"]')
    const firstClick = button.trigger('click')
    const secondClick = button.trigger('click')
    await Promise.all([firstClick, secondClick])
    intersect()
    expect(getFeed).toHaveBeenCalledTimes(2)
    expect(button.attributes('disabled')).toBeDefined()
    pending.resolve(response([4, 3], [], 3))
    await vi.waitFor(() => expect(button.attributes('disabled')).toBeUndefined())
    getFeed.mockResolvedValueOnce(response([2]))
    intersect()

    await vi.waitFor(() => expect(getFeed).toHaveBeenCalledTimes(3))
    expect(getFeed).toHaveBeenLastCalledWith({ scopeType: 'TEAM', scopeId: '7', cursor: 3 })
    await vi.waitFor(() =>
      expect(wrapper.findAll('[data-testid="refresh-post"]').map((p) => p.text())).toEqual([
        '4',
        '3',
        '2',
      ]),
    )
    expect(wrapper.find('[data-testid="timeline-load-more-target"]').exists()).toBe(false)
  })

  it('AC-3: 追加読み込み中は手動更新を並列実行しない', async () => {
    getFeed.mockResolvedValueOnce(response([2], [], 2))
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
      global: { stubs },
    })
    const pending = deferredResponse()
    getFeed.mockReturnValueOnce(pending.promise)
    intersect()
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')
    expect(getFeed).toHaveBeenCalledTimes(2)
    pending.resolve(response([1]))
    await vi.waitFor(() =>
      expect(
        wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled'),
      ).toBeUndefined(),
    )
  })

  it('AC-5: 失敗時は投稿とピンを保持し、通知後に再試行できる', async () => {
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
      global: { stubs },
    })
    getFeed.mockRejectedValueOnce(new Error('offline'))

    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')

    await vi.waitFor(() => expect(showError).toHaveBeenCalledOnce())
    expect(wrapper.findAll('[data-testid="refresh-post"]').map((p) => p.text())).toEqual([
      '10',
      '2',
      '1',
    ])
    expect(wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled')).toBeUndefined()
    getFeed.mockResolvedValueOnce(response([5], [12]))
    await wrapper.get('[data-testid="timeline-refresh"]').trigger('click')
    await vi.waitFor(() =>
      expect(wrapper.findAll('[data-testid="refresh-post"]').map((p) => p.text())).toEqual([
        '12',
        '5',
      ]),
    )
  })

  it('AC-6: 読み込み中でも投稿後などの公開refresh要求は捨てない', async () => {
    const pending = deferredResponse()
    getFeed.mockReturnValueOnce(pending.promise)
    const wrapper = await mountSuspended(TimelineFeed, {
      props: { scopeType: 'TEAM', scopeId: '7' },
      global: { stubs },
    })

    ;(wrapper.vm as unknown as { refresh: () => void }).refresh()

    expect(getFeed).toHaveBeenCalledTimes(2)
    expect(getFeed).toHaveBeenLastCalledWith({ scopeType: 'TEAM', scopeId: '7', cursor: undefined })
    pending.resolve(response([1]))
    await vi.waitFor(() =>
      expect(
        wrapper.get('[data-testid="timeline-refresh"]').attributes('disabled'),
      ).toBeUndefined(),
    )
  })
})
