import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_PRIVACY = {
  data: {
    handleSearchable: true,
    contactApprovalRequired: true,
    dmReceiveFrom: 'CONTACTS_ONLY',
    onlineVisibility: 'NOBODY',
  },
}

test.describe('SET-CNT-001〜004: 連絡先プライバシー設定', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/users/me/contact-privacy**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PRIVACY),
      })
    })
  })

  test('SET-CNT-001: 連絡先プライバシー設定ページが表示される', async ({ page }) => {
    await page.goto('/settings/contact-privacy')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '連絡先プライバシー設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-CNT-002: 設定フォームの各項目が表示される', async ({ page }) => {
    await page.goto('/settings/contact-privacy')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '連絡先プライバシー設定' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('@ハンドルで検索を許可')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('連絡先追加に承認が必要')).toBeVisible()
    await expect(page.getByText('DMを受信できる相手')).toBeVisible()
    await expect(page.getByText('オンライン状態の公開範囲')).toBeVisible()
  })

  test('SET-CNT-003: 保存ボタンをクリックするとPUTリクエストが送信される', async ({ page }) => {
    let putCalled = false
    await page.route('**/api/v1/users/me/contact-privacy**', async (route) => {
      if (route.request().method() === 'PUT') {
        putCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PRIVACY),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_PRIVACY),
        })
      }
    })

    await page.goto('/settings/contact-privacy')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '連絡先プライバシー設定' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '保存' })).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '保存' }).click()

    await expect(async () => {
      expect(putCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('SET-CNT-004: 設定ページから設定一覧へのナビゲーションリンクが存在する', async ({
    page,
  }) => {
    await page.goto('/settings')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '設定' })).toBeVisible({ timeout: 10_000 })
    // 個別設定一覧アコーディオンを開く
    await page.getByRole('button', { name: /個別設定一覧/ }).click()
    await expect(page.getByText('連絡先プライバシー')).toBeVisible({ timeout: 5_000 })
  })
})
