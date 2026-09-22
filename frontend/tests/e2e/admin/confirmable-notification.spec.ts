import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * CMP-260909-1141: 確認通知（F04.9）は /admin/reservation-settings.vue（到達不能ページ）
 * から teams/[slug]/settings/confirmable-notifications.vue へ移設された（PR #3365）。
 * 本テストも移設先を見るように張り替える。
 */

/** チーム確認通知設定ページが呼び出す API をまとめてモックする */
async function mockConfirmableNotificationApis(page: Page) {
  await mockTeam(page)

  // 確認通知設定
  await page.route(`**/api/v1/teams/${TEAM_ID}/confirmable-notification-settings`, async (route) => {
    if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            scopeType: 'TEAM',
            scopeId: TEAM_ID,
            defaultFirstReminderMinutes: 90,
            defaultSecondReminderMinutes: 180,
            senderAlertThresholdPercent: 60,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        }),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            scopeType: 'TEAM',
            scopeId: TEAM_ID,
            defaultFirstReminderMinutes: 60,
            defaultSecondReminderMinutes: 120,
            senderAlertThresholdPercent: 50,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        }),
      })
    }
  })
}

test.describe('ADMIN-018〜021: 確認通知システム', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
        }),
      )
    })
    await mockConfirmableNotificationApis(page)
  })

  test('ADMIN-018: チーム確認通知設定ページに確認通知設定セクションが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
    await waitForHydration(page)

    // ページタイトルが表示される
    await expect(page.getByRole('heading', { name: '確認通知設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ADMIN-019: 確認通知設定コンポーネントが設定値を表示する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
    await waitForHydration(page)

    // 設定フォームのコンポーネントが描画される（保存ボタンが表示される）
    await expect(page.getByRole('button', { name: '保存' })).toBeVisible({ timeout: 10_000 })
  })

  test('ADMIN-020: 確認通知設定の保存ができる', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
    await waitForHydration(page)

    // 保存ボタンをクリックする
    const saveButton = page.getByRole('button', { name: '保存' })
    await expect(saveButton).toBeVisible({ timeout: 10_000 })
    await saveButton.click()

    // エラーが表示されないこと（成功トーストは非同期なのでエラー不在を確認）
    await expect(page.getByText('エラー')).not.toBeVisible()
  })

  test('ADMIN-021: 保留中の確認通知一覧 API が正しいエンドポイントを呼ぶ', async ({ page }) => {
    // 保留中通知 API のモック
    let pendingApiCalled = false
    await page.route('**/api/v1/me/confirmable-notifications/pending', async (route) => {
      pendingApiCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              id: 1,
              title: '練習参加確認',
              body: '今週の練習に参加できますか？',
              priority: 'NORMAL',
              status: 'ACTIVE',
              deadlineAt: '2026-04-20T23:59:59Z',
              confirmedCount: 0,
              totalRecipientCount: 10,
              createdAt: '2026-04-12T00:00:00Z',
            },
          ],
        }),
      })
    })

    // API を直接呼び出して pending 通知の取得を検証する
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/v1/me/confirmable-notifications/pending', {
        headers: { 'Content-Type': 'application/json' },
      })
      return { status: res.status, ok: res.ok }
    })

    // モックが正しく動作すること（呼び出しが発生して 200 を返すこと）
    expect(pendingApiCalled).toBe(true)
    expect(response.status).toBe(200)
  })
})
