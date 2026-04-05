import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_PROFILE = {
  data: {
    id: 1,
    displayName: 'テストユーザー',
    email: 'test@example.com',
    phoneNumber: '090-1234-5678',
    avatarUrl: null,
    hasPassword: true,
  },
}

const MOCK_HANDLE_EMPTY = {
  data: {
    contactHandle: null,
    handleSearchable: true,
    contactApprovalRequired: true,
    onlineVisibility: 'NOBODY',
  },
}

const MOCK_HANDLE_SET = {
  data: {
    contactHandle: 'my_handle',
    handleSearchable: true,
    contactApprovalRequired: true,
    onlineVisibility: 'NOBODY',
  },
}

test.describe('SET-CNT-030〜034: @ハンドル設定（プロフィールページ）', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/users/me**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_PROFILE),
      })
    })
  })

  test('SET-CNT-030: プロフィールページに@ハンドルセクションが表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/contact-handle**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_EMPTY),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('heading', { name: '@ハンドル' })).toBeVisible({ timeout: 5_000 })
  })

  test('SET-CNT-031: 既存ハンドルが入力欄に表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/contact-handle**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_SET),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'プロフィール設定' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('input[placeholder="handle_name"]')).toHaveValue('my_handle')
  })

  test('SET-CNT-032: @記号がプレフィックスとして表示される', async ({ page }) => {
    await page.route('**/api/v1/users/me/contact-handle**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_EMPTY),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '@ハンドル' })).toBeVisible({ timeout: 5_000 })
    // @プレフィックス span
    await expect(page.locator('span').filter({ hasText: /^@$/ })).toBeVisible()
  })

  test('SET-CNT-033: 利用可能なハンドルを入力すると「使用できます」が表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/users/me/contact-handle**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_EMPTY),
      })
    })
    await page.route('**/api/v1/users/contact-handle-check**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ available: true }),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible({ timeout: 5_000 })
    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('new_handle', { delay: 30 })

    await expect(page.getByText('使用できます')).toBeVisible({ timeout: 5_000 })
  })

  test('SET-CNT-034: 重複したハンドルを入力すると「既に使用されています」が表示される', async ({
    page,
  }) => {
    await page.route('**/api/v1/users/me/contact-handle**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_HANDLE_EMPTY),
      })
    })
    await page.route('**/api/v1/users/contact-handle-check**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ available: false }),
      })
    })

    await page.goto('/settings/profile')
    await waitForHydration(page)

    await expect(page.locator('input[placeholder="handle_name"]')).toBeVisible({ timeout: 5_000 })
    const input = page.locator('input[placeholder="handle_name"]')
    await input.click()
    await input.pressSequentially('taken_handle', { delay: 30 })

    await expect(page.getByText('このハンドルは既に使用されています')).toBeVisible({
      timeout: 5_000,
    })
  })
})
