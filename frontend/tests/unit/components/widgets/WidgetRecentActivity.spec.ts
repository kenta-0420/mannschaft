import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'

/**
 * F03.18 WidgetRecentActivity.vue のユニットテスト（回帰: P1-3）。
 *
 * <p>欠陥: ウィジェットの map が `detail` を一切コピーしておらず、差分表示が常に空になっていた。
 * さらに `targetTitle: a.summary` としていたため、SCHEDULE 系では ActivityType 固定文言
 * （「予定を更新しました」）が出るだけで予定名が表示されなかった。</p>
 *
 * <p>検証する軸:
 *  - RA-001: SCHEDULE 系では targetTitle が detail.title になる
 *  - RA-002: detail.fields が ActivityItem の detailFields へ渡り差分が描画される
 *  - RA-003: detail が null の既存7種別は summary にフォールバックし差分行を出さない</p>
 */

const mockGetActivity = vi.fn()

vi.mock('~/composables/useDashboardApi', () => ({
  useDashboardApi: () => ({
    getActivity: mockGetActivity,
  }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: vi.fn(),
    capture: vi.fn(),
  }),
}))

const WidgetRecentActivity = (
  await import('~/components/widgets/WidgetRecentActivity.vue')
).default

beforeEach(() => {
  setActivePinia(createPinia())
  mockGetActivity.mockReset()
})

describe('WidgetRecentActivity.vue', () => {
  it('RA-001/RA-002: SCHEDULE 系は detail.title を表示し、detail.fields が差分行として描画される', async () => {
    mockGetActivity.mockResolvedValue({
      data: {
        items: [
          {
            id: 1,
            type: 'SCHEDULE_RESCHEDULED',
            actor: { id: 5, displayName: '田中太郎', avatarUrl: null },
            scopeType: 'TEAM',
            scopeId: '42',
            scopeName: '第一営業チーム',
            targetType: 'SCHEDULE',
            targetId: 12345,
            summary: '予定の日程を変更しました',
            detail: {
              scheduleId: 12345,
              title: '定例会議',
              fields: [
                { field: 'startAt', before: '2026-08-10T19:00:00', after: '2026-08-17T19:00:00' },
              ],
              affectedCount: 1,
            },
            createdAt: '2026-08-05T10:30:00',
          },
        ],
        nextCursor: null,
      },
    })

    const wrapper = await mountSuspended(WidgetRecentActivity)
    const text = wrapper.text()

    // 表示用タイトルの正本は detail.title（summary ではない）
    expect(text).toContain('定例会議')
    // 差分（fields）が 3 行目に出る
    expect(text).toContain('2026-08-10T19:00:00')
    expect(text).toContain('2026-08-17T19:00:00')
  })

  it('RA-003: detail が null の既存種別は summary にフォールバックし差分行を出さない', async () => {
    mockGetActivity.mockResolvedValue({
      data: {
        items: [
          {
            id: 2,
            type: 'POST_CREATED',
            actor: { id: 6, displayName: '鈴木花子', avatarUrl: null },
            scopeType: 'TEAM',
            scopeId: '42',
            scopeName: '第一営業チーム',
            targetType: 'TIMELINE_POST',
            targetId: 999,
            summary: '新しい投稿を作成しました',
            detail: null,
            createdAt: '2026-08-05T10:30:00',
          },
        ],
        nextCursor: null,
      },
    })

    const wrapper = await mountSuspended(WidgetRecentActivity)
    const text = wrapper.text()

    expect(text).toContain('新しい投稿を作成しました')
    expect(text).not.toContain('→')
  })
})
