import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const MOCK_CHANNELS = {
  data: [],
  meta: { page: 0, size: 50, totalElements: 0, totalPages: 0 },
}

const MOCK_CONTACTS = {
  data: [
    {
      folderItemId: 1,
      folderId: null,
      user: {
        id: 10,
        displayName: '田中 花子',
        contactHandle: 'hanako_tanaka',
        avatarUrl: null,
      },
      customName: null,
      isPinned: false,
      privateNote: null,
      addedAt: '2026-03-15T10:00:00Z',
    },
    {
      folderItemId: 2,
      folderId: null,
      user: {
        id: 11,
        displayName: '鈴木 一郎',
        contactHandle: 'ichiro_suzuki',
        avatarUrl: null,
      },
      customName: 'イチロー',
      isPinned: true,
      privateNote: null,
      addedAt: '2026-03-20T10:00:00Z',
    },
  ],
  meta: { nextCursor: null, total: 2 },
}

const MOCK_RECEIVED_REQUESTS = {
  data: [
    {
      id: 5,
      requester: {
        id: 20,
        displayName: '山本 太郎',
        contactHandle: 'taro_yamamoto',
        avatarUrl: null,
      },
      target: { id: 1, displayName: '自分', contactHandle: 'myself', avatarUrl: null },
      status: 'PENDING',
      message: 'よろしくお願いします',
      sourceType: 'HANDLE_SEARCH',
      createdAt: '2026-04-03T10:00:00Z',
    },
  ],
}

const MOCK_SENT_REQUESTS = { data: [] }

test.describe('CNT-LIST-001〜006: 連絡先一覧（チャットページ）', () => {
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
        body: JSON.stringify(MOCK_RECEIVED_REQUESTS),
      })
    })
    await page.route('**/api/v1/contact-requests/sent**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SENT_REQUESTS),
      })
    })
  })

  test('CNT-LIST-001: チャットページに連絡先タブが表示される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '連絡先' })).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: '申請' })).toBeVisible()
  })

  test('CNT-LIST-002: 連絡先タブをクリックすると連絡先一覧が表示される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
    await page.getByRole('button', { name: '連絡先' }).click()

    await expect(page.getByText('田中 花子')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('@hanako_tanaka')).toBeVisible()
  })

  test('CNT-LIST-003: カスタム名が設定されている連絡先はカスタム名で表示される', async ({
    page,
  }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()

    await expect(page.getByText('イチロー')).toBeVisible({ timeout: 5_000 })
  })

  test('CNT-LIST-004: @ハンドルで追加ボタンが表示される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()

    await expect(page.getByRole('button', { name: '@ハンドルで追加' })).toBeVisible({
      timeout: 5_000,
    })
  })

  test('CNT-LIST-005: 申請タブに受信申請が表示される', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '申請' }).click()

    await expect(page.getByText('山本 太郎')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('よろしくお願いします')).toBeVisible()
    await expect(page.getByRole('button', { name: '承認' })).toBeVisible()
    await expect(page.getByRole('button', { name: '拒否' })).toBeVisible()
  })

  test('CNT-LIST-006: 申請タブの送信済みに切り替えられる', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '申請' }).click()
    await expect(page.getByText('山本 太郎')).toBeVisible({ timeout: 5_000 })

    await page.getByRole('button', { name: '送信済み' }).click()
    await expect(page.getByText('送信済みの申請はありません')).toBeVisible({ timeout: 5_000 })
  })
})

test.describe('CNT-LIST-007〜009: 連絡先操作', () => {
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
        body: JSON.stringify(MOCK_RECEIVED_REQUESTS),
      })
    })
    await page.route('**/api/v1/contact-requests/sent**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SENT_REQUESTS),
      })
    })
  })

  test('CNT-LIST-007: 申請承認でPOSTリクエストが送信される', async ({ page }) => {
    let acceptCalled = false
    await page.route('**/api/v1/contact-requests/*/accept**', async (route) => {
      acceptCalled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '申請' }).click()
    await expect(page.getByRole('button', { name: '承認' })).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '承認' }).click()

    await expect(async () => {
      expect(acceptCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('CNT-LIST-008: 申請拒否でPOSTリクエストが送信される', async ({ page }) => {
    let rejectCalled = false
    await page.route('**/api/v1/contact-requests/*/reject**', async (route) => {
      rejectCalled = true
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '申請' }).click()
    await expect(page.getByRole('button', { name: '拒否' })).toBeVisible({ timeout: 5_000 })
    await page.getByRole('button', { name: '拒否' }).click()

    await expect(async () => {
      expect(rejectCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('CNT-LIST-009: @ハンドルで追加ダイアログが開く', async ({ page }) => {
    await page.goto('/chat')
    await waitForHydration(page)

    await page.getByRole('button', { name: '連絡先' }).click()
    await expect(page.getByRole('button', { name: '@ハンドルで追加' })).toBeVisible({
      timeout: 5_000,
    })
    await page.getByRole('button', { name: '@ハンドルで追加' }).click()

    await expect(page.getByRole('dialog', { name: '@ハンドルで連絡先を追加' })).toBeVisible({
      timeout: 5_000,
    })
  })
})
