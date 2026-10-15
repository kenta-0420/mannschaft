import { test, expect } from '@playwright/test'
import { gotoAuthed } from './helpers'

/**
 * モバイルUX根治戦役 — F隊向け受け入れ条件（AC-13）の red テスト。
 *
 * 対象:
 * - /notifications（NotificationList.vue）: スヌーズ/既読トグルボタンが `p-1` パディングのみの
 *   アイコンボタン（実測相当 ~20x20px）で 44x44 に満たない。
 * - /timeline（TimelinePostCard.vue）: 反応(mitayo)/コメント/リポスト/ブックマークの
 *   アクションバーが `text-xs` のアイコン+テキストのみで明示的な高さ指定が無い。
 * - /chat（ChatTabBar.vue）: 「＋」タブ追加ボタンが `h-7 w-7`(28x28px) 固定。
 */

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

test.describe('MOBILE-TAP-TARGETS: 390px タップターゲット受け入れ条件', () => {
  test('MTT-01: /notifications の各通知行のアクションボタン（スヌーズ・既読）が44x44以上', async ({ page }) => {
    await page.route('**/api/v1/notifications**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [1, 2, 3].map((i) => ({
            id: i,
            notificationType: 'GENERAL',
            priority: 'NORMAL',
            title: `テスト通知${i}`,
            body: null,
            sourceType: 'X',
            sourceId: null,
            scopeType: 'PERSONAL',
            scopeId: null,
            scopeName: null,
            actionUrl: null,
            actor: null,
            isRead: false,
            readAt: null,
            snoozedUntil: null,
            createdAt: new Date().toISOString(),
          })),
          meta: { nextCursor: null, limit: 20, hasNext: false },
        }),
      })
    })

    await gotoAuthed(page, '/notifications')
    await page.waitForTimeout(1200)

    const snoozeButtons = page.locator('button[aria-label="スヌーズ"]')
    const count = await snoozeButtons.count()
    expect(count, 'スヌーズボタンが見つからない（モック通知3件を投入済み）').toBeGreaterThan(0)

    const tooSmall: string[] = []
    for (let i = 0; i < count; i++) {
      const box = await snoozeButtons.nth(i).boundingBox()
      if (box && (box.width < 44 || box.height < 44)) {
        tooSmall.push(`snooze[${i}] ${Math.round(box.width)}x${Math.round(box.height)}`)
      }
    }
    const readToggleButtons = page.locator('button[title="既読にする"], button[title="未読にする"]')
    const readCount = await readToggleButtons.count()
    for (let i = 0; i < readCount; i++) {
      const box = await readToggleButtons.nth(i).boundingBox()
      if (box && (box.width < 44 || box.height < 44)) {
        tooSmall.push(`read-toggle[${i}] ${Math.round(box.width)}x${Math.round(box.height)}`)
      }
    }

    expect(tooSmall, `44x44未満のアクションボタン: ${JSON.stringify(tooSmall)}`).toEqual([])
  })

  test('MTT-02: /timeline の投稿カードの反応・コメント・ブックマークボタンが44x44以上', async ({ page }) => {
    await page.route('**/api/v1/timeline/feed**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            pinned: [],
            posts: [
              {
                id: 1,
                scope: { scopeType: 'PUBLIC', scopeId: '0', name: null, slug: null },
                author: { userId: 1, socialProfileId: null, postedAsType: null, postedAsId: null },
                content: { content: 'モバイルタップターゲット検証用の投稿本文です', parentId: null, repostOfId: null, status: 'PUBLISHED', scheduledAt: null, isPinned: false },
                stats: { repostCount: 0, reactionCount: 0, replyCount: 2, attachmentCount: 0, editCount: 0 },
                audit: { createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() },
                user: { id: 1, displayName: 'テストユーザー', avatarUrl: null },
                postedAs: null,
                isBookmarked: false,
                isEdited: false,
                isTruncated: false,
                mitayo: false,
                mitayoCount: 0,
                attachments: [],
                repostOf: null,
                poll: null,
              },
            ],
          },
          meta: { nextCursor: null, limit: 20, hasNext: false },
        }),
      })
    })

    await gotoAuthed(page, '/timeline')
    await page.waitForTimeout(1200)

    const targets = [
      { label: '反応(mitayo)', locator: page.locator('[data-testid="team-timeline-like"]').first() },
      { label: 'コメント', locator: page.locator('[data-testid="team-timeline-reply-btn"]').first() },
      { label: 'ブックマーク', locator: page.locator('button:has(.pi-bookmark), button:has(.pi-bookmark-fill)').first() },
    ]

    const tooSmall: string[] = []
    for (const t of targets) {
      await expect(t.locator, `${t.label}ボタンが見つからない`).toBeVisible({ timeout: 5_000 })
      const box = await t.locator.boundingBox()
      if (box && (box.width < 44 || box.height < 44)) {
        tooSmall.push(`${t.label} ${Math.round(box.width)}x${Math.round(box.height)}`)
      }
    }

    expect(tooSmall, `44x44未満のアクションボタン: ${JSON.stringify(tooSmall)}`).toEqual([])
  })

  test('MTT-03: /chat のタブ追加「＋」ボタンが44x44以上', async ({ page }) => {
    await page.route('**/api/v1/chat/channels?**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
    })

    await gotoAuthed(page, '/chat')
    await page.waitForTimeout(1200)

    const addBtn = page.getByRole('button', { name: '新しいタブを開く' })
    await expect(addBtn, '「＋」タブ追加ボタンが見つからない').toBeVisible({ timeout: 5_000 })
    const box = await addBtn.boundingBox()
    expect(box, '「＋」ボタンの座標が取得できない').not.toBeNull()
    expect(
      box!.width >= 44 && box!.height >= 44,
      `「＋」タブ追加ボタンのヒット領域(${Math.round(box!.width)}x${Math.round(box!.height)})が44x44を下回っている`,
    ).toBe(true)
  })
})
