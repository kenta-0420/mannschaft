import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F09.17 Phase 11-c-5 smoke E2E
 *
 * お知らせフィードに isAdvertisement=true のアイテムが含まれる場合、
 * AnnouncementItem は AdLabelBadge（「広告」ラベル）を併記表示すること。
 *
 * 景品表示法対応の根幹。Phase 11-c-2 で実装済のロジックが正しく動くかを
 * smoke で検証する（i18n の ad_label がレンダリングされること込み）。
 */

const TEAM_ID = 1

const NORMAL_ITEM = {
  id: 1001,
  scopeType: 'TEAM',
  scopeId: TEAM_ID,
  sourceType: 'TIMELINE_POST',
  sourceId: 9001,
  sourceUrl: '/teams/1/timeline/9001',
  title: '通常のお知らせ',
  excerpt: '通常のお知らせ本文',
  priority: 'NORMAL',
  isPinned: false,
  pinnedAt: null,
  visibility: 'MEMBERS_ONLY',
  author: { id: 1, displayName: 'テストユーザー', avatarUrl: null },
  sourceMeta: null,
  isRead: false,
  startsAt: null,
  expiresAt: null,
  createdAt: '2026-05-17T00:00:00Z',
}

const AD_ITEM = {
  ...NORMAL_ITEM,
  id: 1002,
  title: 'スポンサー提供のお知らせ',
  excerpt: 'プロモーション本文',
  isAdvertisement: true,
  advertiserAccountId: 1,
  messagingCampaignId: '11111111-2222-3333-4444-555555555555',
  channelType: 'ANNOUNCEMENT',
}

test.describe('F09.17 Phase 11-c-5: 広告ラベル表示 (smoke)', () => {
  test('isAdvertisement=true のお知らせには「広告」バッジが表示される', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/announcements**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [AD_ITEM, NORMAL_ITEM],
          meta: { nextCursor: null, limit: 20, unreadCount: 2, totalCount: 2, hasNext: false },
        }),
      })
    })

    // 関連 API が呼ばれた場合のフォールバック（権限・既読など）
    await page.route('**/api/v1/teams/1/role-access**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { roles: ['MEMBER'] } }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/announcements`)
    await waitForHydration(page)

    // 広告ラベルバッジが表示される（i18n の ja=「広告」）
    const badge = page.getByTestId('ad-label-badge').first()
    await expect(badge).toBeVisible({ timeout: 10_000 })
    await expect(badge).toHaveText('広告')

    // 広告アイテムのタイトルも表示されていること
    await expect(page.getByText('スポンサー提供のお知らせ')).toBeVisible()
  })
})
