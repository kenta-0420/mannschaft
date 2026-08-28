import { describe, it, expect, vi, beforeEach } from 'vitest'
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
  getReplies: vi.fn().mockResolvedValue({ data: { posts: [] }, meta: { nextCursor: null, hasNext: false } }),
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
      posts: [
        makePost(1, 'ORGANIZATION', '5', 'さくら学園'),
        makePost(2, 'TEAM', '7', '一軍'),
      ],
    },
    meta: { nextCursor: null, limit: 20, hasNext: false },
  }
}

/** 指定カードのケバブメニュー項目（Menu の model）を取り出す。 */
function menuModelAt(
  wrapper: Awaited<ReturnType<typeof mountSuspended>>,
  index: number,
): Array<{ label: string, command: () => void }> {
  const menus = wrapper.findAllComponents(Menu)
  return menus[index]!.props('model') as Array<{ label: string, command: () => void }>
}

describe('TimelineFeed.vue — ミュート導線', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMyTimeline.mockImplementation(async () => feedResponse())
    getFeed.mockImplementation(async () => feedResponse())
    getMutes.mockResolvedValue({ data: [] })
    addMute.mockImplementation(async (p: { mutedType: 'TEAM' | 'ORGANIZATION', mutedId: number }) => ({
      data: makeMute(99, p.mutedType, p.mutedId),
    }))
    removeMute.mockResolvedValue(undefined)
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
