import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_TOKENS = {
  data: [
    {
      id: 1,
      token: '550e8400-e29b-41d4-a716-446655440000',
      label: 'SNS用',
      inviteUrl: 'http://localhost:3000/contact-invite/550e8400-e29b-41d4-a716-446655440000',
      qrCodeUrl: '/api/v1/contact-invite-tokens/550e8400-e29b-41d4-a716-446655440000/qr',
      maxUses: 10,
      usedCount: 2,
      expiresAt: '2026-12-31T00:00:00Z',
      createdAt: '2026-04-01T00:00:00Z',
    },
  ],
}

const MOCK_NEW_TOKEN = {
  data: {
    id: 2,
    token: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    label: 'テスト用',
    inviteUrl: 'http://localhost:3000/contact-invite/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    qrCodeUrl: '/api/v1/contact-invite-tokens/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/qr',
    maxUses: 1,
    usedCount: 0,
    expiresAt: '2026-04-11T00:00:00Z',
    createdAt: '2026-04-04T00:00:00Z',
  },
}

test.describe('SET-CNT-010〜014: 招待トークン管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/contact-invite-tokens**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_TOKENS),
        })
      } else if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_NEW_TOKEN),
        })
      } else if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 })
      }
    })
  })

  test('SET-CNT-010: 招待URL管理ページが表示される', async ({ page }) => {
    await page.goto('/settings/contact-invite-tokens')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '招待URL管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SET-CNT-011: 発行済みトークンが一覧表示される', async ({ page }) => {
    await page.goto('/settings/contact-invite-tokens')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '招待URL管理' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('SNS用')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('2/10回')).toBeVisible()
  })

  test('SET-CNT-012: 新しいURLを発行フォームが開閉できる', async ({ page }) => {
    await page.goto('/settings/contact-invite-tokens')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '招待URL管理' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: '新しいURLを発行' }).click()
    await expect(page.getByRole('button', { name: '発行', exact: true })).toBeVisible({
      timeout: 3_000,
    })
    await page.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('button', { name: '発行', exact: true })).not.toBeVisible()
  })

  test('SET-CNT-013: 新しいトークンを発行できる', async ({ page }) => {
    let postCalled = false
    await page.route('**/api/v1/contact-invite-tokens**', async (route) => {
      if (route.request().method() === 'POST') {
        postCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_NEW_TOKEN),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_TOKENS),
        })
      }
    })

    await page.goto('/settings/contact-invite-tokens')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '招待URL管理' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: '新しいURLを発行' }).click()
    await expect(page.getByRole('button', { name: '発行', exact: true })).toBeVisible({
      timeout: 3_000,
    })
    await page.getByRole('button', { name: '発行', exact: true }).click()

    await expect(async () => {
      expect(postCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('SET-CNT-014: 無効化ボタンでDELETEリクエストが送信される', async ({ page }) => {
    let deleteCalled = false
    await page.route('**/api/v1/contact-invite-tokens/**', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      }
    })

    await page.goto('/settings/contact-invite-tokens')
    await waitForHydration(page)

    await expect(page.getByText('SNS用')).toBeVisible({ timeout: 10_000 })
    // 無効化ボタン（ゴミ箱アイコン）をクリック
    await page.locator('.pi-trash').first().click()

    await expect(async () => {
      expect(deleteCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })
})
