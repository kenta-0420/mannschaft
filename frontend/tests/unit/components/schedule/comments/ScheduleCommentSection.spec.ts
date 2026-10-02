import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ScheduleCommentSection from '~/components/schedule/comments/ScheduleCommentSection.vue'

/**
 * F03.16 予定コメントスレッド — ScheduleCommentSection.vue ユニットテスト。
 *
 * 検証観点（設計書 §9 の一部を FE 層で観測可能な粒度に落としたもの）:
 *   SCS-001: comments_enabled=false のとき、締切理由（closed）を表示し投稿フォームは出さない
 *   SCS-002: canPost=true のとき、投稿フォームが表示される
 *   SCS-003: トゥームストーン（isDeleted=true）は「削除されました」の枠のみ表示し、本文・投稿者を表示しない
 */

const mockGetMeta = vi.fn()
const mockListComments = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useScheduleComments', () => () => ({
  listComments: mockListComments,
  getMeta: mockGetMeta,
  listReplies: vi.fn().mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
  mentionCandidates: vi.fn().mockResolvedValue({ data: [] }),
  createComment: vi.fn(),
  updateComment: vi.fn(),
  deleteComment: vi.fn(),
  updateSettings: vi.fn(),
}))

function commentsPage(data: unknown[]) {
  return { data, meta: { total: data.length, page: 0, size: 20, totalPages: 1 } }
}

beforeEach(() => {
  mockGetMeta.mockReset()
  mockListComments.mockReset()
})

describe('ScheduleCommentSection.vue', () => {
  it('SCS-001: comments_enabled=false のとき締切理由を表示し投稿フォームは出さない', async () => {
    mockListComments.mockResolvedValue(commentsPage([]))
    mockGetMeta.mockResolvedValue({
      data: { scheduleId: 1, commentsEnabled: false, canPost: false, canPostReason: 'CLOSED' },
    })

    const wrapper = await mountSuspended(ScheduleCommentSection, {
      props: { scheduleId: 1 },
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="schedule-comment-cannot-post"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="schedule-comment-form"]').exists()).toBe(false)
  })

  it('SCS-002: canPost=true のとき投稿フォームが表示される', async () => {
    mockListComments.mockResolvedValue(commentsPage([]))
    mockGetMeta.mockResolvedValue({
      data: { scheduleId: 1, commentsEnabled: true, canPost: true },
    })

    const wrapper = await mountSuspended(ScheduleCommentSection, {
      props: { scheduleId: 1 },
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="schedule-comment-form"]').exists()).toBe(true)
  })

  it('SCS-003: トゥームストーンは本文・投稿者を出さず「削除されました」の枠のみ表示する', async () => {
    mockListComments.mockResolvedValue(
      commentsPage([
        {
          id: 'c1',
          scheduleId: 1,
          parentId: null,
          rootId: null,
          depth: 0,
          body: null,
          isEdited: false,
          isDeleted: true,
          replyCount: 1,
          author: null,
          canEdit: false,
          canDelete: false,
          createdAt: '2026-08-05T10:00:00',
          updatedAt: '2026-08-05T10:00:00',
          replies: [
            {
              id: 'c2',
              scheduleId: 1,
              parentId: 'c1',
              rootId: 'c1',
              depth: 1,
              body: '駅前で大丈夫です！',
              isEdited: false,
              isDeleted: false,
              replyCount: 0,
              author: { userId: 102, displayName: '鈴木 花子', avatarUrl: null },
              canEdit: false,
              canDelete: false,
              createdAt: '2026-08-05T10:05:00',
              updatedAt: '2026-08-05T10:05:00',
              replies: null,
            },
          ],
        },
      ]),
    )
    mockGetMeta.mockResolvedValue({
      data: { scheduleId: 1, commentsEnabled: true, canPost: true },
    })

    const wrapper = await mountSuspended(ScheduleCommentSection, {
      props: { scheduleId: 1 },
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    const tombstone = wrapper.find('[data-testid="schedule-comment-item-c1"]')
    expect(tombstone.exists()).toBe(true)
    expect(tombstone.text()).toContain('schedule.comment.deleted')
    expect(tombstone.text()).not.toContain('退会')

    // 生存返信はそのまま表示される（親を消しても子の文脈は保つ・§5.3）
    expect(wrapper.text()).toContain('駅前で大丈夫です！')
  })
})
