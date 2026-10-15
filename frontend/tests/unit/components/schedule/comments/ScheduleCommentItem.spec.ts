import { describe, it, expect } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ScheduleCommentItem from '~/components/schedule/comments/ScheduleCommentItem.vue'

/**
 * F03.16 予定コメントスレッド — ScheduleCommentItem.vue ユニットテスト。
 *
 * 検証観点:
 *   SCI-001: canEdit=false / canDelete=false の他人のコメントには編集・削除ボタンを出さない
 *   SCI-002: canEdit=true / canDelete=true の自分のコメントには編集・削除ボタンを出す
 *   SCI-003: depth=1（返信）には「返信」ボタンを出さない（深さ上限1・§3.3.1）
 *   SCI-004: isEdited=true のとき「（編集済み）」を表示する
 */

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

function baseComment(overrides: Record<string, unknown> = {}) {
  return {
    id: 'c1',
    scheduleId: 1,
    parentId: null,
    rootId: null,
    depth: 0,
    body: '集合場所は駅前でよいですか？',
    isEdited: false,
    isDeleted: false,
    replyCount: 0,
    author: { userId: 101, displayName: '山田 太郎', avatarUrl: null },
    canEdit: false,
    canDelete: false,
    createdAt: '2026-08-05T10:00:00',
    updatedAt: '2026-08-05T10:00:00',
    replies: null,
    ...overrides,
  }
}

describe('ScheduleCommentItem.vue', () => {
  it('SCI-001: canEdit=false / canDelete=false の他人のコメントには編集・削除ボタンを出さない', async () => {
    const wrapper = await mountSuspended(ScheduleCommentItem, {
      props: {
        comment: baseComment({ canEdit: false, canDelete: false }),
        canReply: true,
        editing: false,
        editSubmitting: false,
      },
    })
    expect(wrapper.find('[aria-label="schedule.comment.edit"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="schedule.comment.delete"]').exists()).toBe(false)
  })

  it('SCI-002: canEdit=true / canDelete=true の自分のコメントには編集・削除ボタンを出す', async () => {
    const wrapper = await mountSuspended(ScheduleCommentItem, {
      props: {
        comment: baseComment({ canEdit: true, canDelete: true }),
        canReply: true,
        editing: false,
        editSubmitting: false,
      },
    })
    expect(wrapper.find('[aria-label="schedule.comment.edit"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="schedule.comment.delete"]').exists()).toBe(true)
  })

  it('SCI-003: depth=1（返信）には返信ボタンを出さない', async () => {
    const wrapper = await mountSuspended(ScheduleCommentItem, {
      props: {
        comment: baseComment({ depth: 1, parentId: 'root', rootId: 'root' }),
        canReply: true,
        editing: false,
        editSubmitting: false,
      },
    })
    const replyButtons = wrapper.findAll('button').filter((b) => b.text() === 'schedule.comment.reply')
    expect(replyButtons.length).toBe(0)
  })

  it('SCI-004: isEdited=true のとき編集済み表示を出す', async () => {
    const wrapper = await mountSuspended(ScheduleCommentItem, {
      props: {
        comment: baseComment({ isEdited: true }),
        canReply: true,
        editing: false,
        editSubmitting: false,
      },
    })
    expect(wrapper.text()).toContain('schedule.comment.edited')
  })
})
