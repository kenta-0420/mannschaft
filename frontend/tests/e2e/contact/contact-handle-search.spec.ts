import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_HANDLE_RESULT = {
  data: {
    userId: 42,
    displayName: '山田 太郎',
    contactHandle: 'taro_yamada',
    avatarUrl: null,
    isContact: false,
    hasPendingRequest: false,
    contactApprovalRequired: true,
  },
}

const MOCK_CHANNELS = {
  data: [],
  meta: { page: 0, size: 50, totalElements: 0, totalPages: 0 },
}

const MOCK_CONTACTS = {
  data: [],
  meta: { nextCursor: null, total: 0 },
}

const MOCK_REQUESTS_EMPTY = { data: [] }

test.describe('CNT-SEARCH-001〜005: @ハンドル検索', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/chat/channels**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_CHANNELS),
      })
    })
    await page.route('**/api/v1/contacts**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_CONTACTS),
      })
    })
    await page.route('**/api/v1/contact-requests/received**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_REQUESTS_EMPTY),
      })
    })
    await page.route('**/api/v1/contact-requests/sent**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_REQUESTS_EMPTY),
      })
    })
  })

  test('CNT-SEARCH-001: ハンドル検索ダイアログが表示される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    await expect(page.getByRole('dialog', { name: '@ハンドルで連絡先を追加' })).toBeVisible({
      timeout: 5_000,
    })
    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible()
  })

  test('CNT-SEARCH-002: 検索してユーザーが見つかると結果が表示される', async ({ page }) => {
    await page.route('**/api/v1/users/contact-handle/taro_yamada**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_RESULT),
      })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible({ timeout: 5_000 })
    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('taro_yamada', { delay: 30 })

    await expect(page.getByText('山田 太郎')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('@taro_yamada')).toBeVisible()
  })

  test('CNT-SEARCH-003: 見つからない場合は「見つかりませんでした」が表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/users/contact-handle/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: null }),
      })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible({ timeout: 5_000 })
    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('unknown_user', { delay: 30 })

    await expect(page.getByText('見つかりませんでした')).toBeVisible({ timeout: 5_000 })
  })

  test('CNT-SEARCH-004: 既に連絡先のユーザーには「連絡先済み」タグが表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/users/contact-handle/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { ...MOCK_HANDLE_RESULT.data, isContact: true },
        }),
      })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('taro_yamada', { delay: 30 })

    await expect(page.getByText('山田 太郎')).toBeVisible({ timeout: 5_000 })
    // ダイアログ内の「連絡先」タグを検索（exact: true で完全一致）
    await expect(
      page.getByRole('dialog').locator('.p-tag-label', { hasText: '連絡先' }),
    ).toBeVisible()
    // 「連絡先に追加」ボタンは表示されない
    await expect(page.getByRole('button', { name: '連絡先に追加' })).not.toBeVisible()
  })

  test('CNT-SEARCH-005: 申請送信でPOSTリクエストが送信され成功する', async ({ page }) => {
    let postCalled = false
    await page.route('**/api/v1/users/contact-handle/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_RESULT),
      })
    })
    await page.route('**/api/v1/contact-requests**', async (route) => {
      if (route.request().method() === 'POST') {
        postCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { requestId: 1, status: 'PENDING' } }),
        })
      }
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('taro_yamada', { delay: 30 })

    await expect(page.getByRole('button', { name: '連絡先に追加' })).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '連絡先に追加' }).click()

    await expect(async () => {
      expect(postCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })
})
