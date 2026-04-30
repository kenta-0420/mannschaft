import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { fillInput, selectDropdown, waitForDialog } from '../helpers/form'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

/**
 * 組織版掲示板スレッド作成深掘りテスト。
 *
 * 掲示板APIは organization-scoped ではなく共通の /api/v1/bulletin/* に存在し、
 * BulletinThreadForm は scope-type="ORGANIZATION" を渡してリクエストする。
 * mockOrgFeatureApis は /api/v1/organizations/{id}/** にしか反応しないため、
 * 掲示板APIは個別にモックする必要がある。
 */

/** 掲示板スレッド一覧（空）とカテゴリ一覧をモックする共通ヘルパー */
async function mockBulletinList(page: Page) {
  await page.route('**/api/v1/bulletin/threads?**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
        }),
      })
    } else {
      await route.fallback()
    }
  })
  // カテゴリ一覧
  await page.route('**/api/v1/bulletin/categories?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          { id: 1, name: '組織お知らせ', color: '#3b82f6' },
          { id: 2, name: '理事会連絡', color: '#22c55e' },
          { id: 3, name: '雑談', color: '#f59e0b' },
        ],
      }),
    })
  })
}

test.describe('ORG-DEEP-bulletin: 組織掲示板スレッド作成ダイアログ深掘り', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
    await mockBulletinList(page)
  })

  test('ORG-DEEP-bulletin-001: 「新規スレッド」ボタンを押すとダイアログが開く', async ({
    page,
  }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible({ timeout: 10_000 })

    // BulletinThreadList 内の「新規スレッド」ボタンを探す
    const createButton = page.getByRole('button', { name: '新規スレッド' })
    await expect(createButton).toBeVisible({ timeout: 10_000 })
    await createButton.click()
    await waitForDialog(page)
  })

  test('ORG-DEEP-bulletin-002: タイトルと本文が空のままだと「投稿」ボタンが disabled', async ({
    page,
  }) => {
    let postCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/bulletin/threads') && req.method() === 'POST') {
        postCalled = true
      }
    })

    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const createButton = page.getByRole('button', { name: '新規スレッド' })
    await createButton.click()
    const dialog = await waitForDialog(page)

    // 投稿ボタンは :disabled="!title.trim() || !body.trim()" のため初期は disabled
    const submitBtn = dialog.getByRole('button', { name: '投稿' })
    await expect(submitBtn).toBeDisabled()

    // クリックしても POST は呼ばれない（disabled なのでイベント発火しない）
    await submitBtn.click({ force: true }).catch(() => undefined)
    await page.waitForTimeout(300)
    expect(postCalled).toBe(false)
  })

  test('ORG-DEEP-bulletin-003: カテゴリ Select で組織向け項目を選択できる', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const createButton = page.getByRole('button', { name: '新規スレッド' })
    await createButton.click()
    const dialog = await waitForDialog(page)

    const categorySelect = dialog.locator('label:has-text("カテゴリ") + .p-select')
    await selectDropdown(page, categorySelect, '組織お知らせ')
    await expect(categorySelect).toContainText('組織お知らせ')
  })

  test('ORG-DEEP-bulletin-004: 重要度 Select で「緊急」を選択できる', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const createButton = page.getByRole('button', { name: '新規スレッド' })
    await createButton.click()
    const dialog = await waitForDialog(page)

    const prioritySelect = dialog.locator('label:has-text("重要度") + .p-select')
    await selectDropdown(page, prioritySelect, '緊急')
    await expect(prioritySelect).toContainText('緊急')
  })

  test('ORG-DEEP-bulletin-005: タイトルと本文を入力して投稿すると POST(scopeType=ORGANIZATION) が成功する', async ({
    page,
  }) => {
    let postBody: Record<string, unknown> | null = null
    await page.route('**/api/v1/bulletin/threads', async (route) => {
      if (route.request().method() === 'POST') {
        try {
          postBody = route.request().postDataJSON() as Record<string, unknown>
        } catch {
          postBody = null
        }
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 999,
              title: (postBody as { title?: string } | null)?.title ?? '',
              body: (postBody as { body?: string } | null)?.body ?? '',
              priority: 'INFO',
              scopeType: 'ORGANIZATION',
              scopeId: ORG_ID,
              createdAt: '2026-04-07T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fallback()
      }
    })

    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    const createButton = page.getByRole('button', { name: '新規スレッド' })
    await createButton.click()
    const dialog = await waitForDialog(page)

    await fillInput(dialog.getByPlaceholder('スレッドのタイトル'), '組織E2E深層テストスレッド')
    await fillInput(
      dialog.getByPlaceholder('本文を入力...'),
      '組織版の本文です。深層テストから投稿しました。',
    )

    const respPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/v1/bulletin/threads')
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )
    await dialog.getByRole('button', { name: '投稿' }).click()
    await respPromise

    expect(postBody).not.toBeNull()
    const body = postBody as unknown as { title: string, scopeType?: string }
    expect(body.title).toBe('組織E2E深層テストスレッド')
    // BulletinThreadForm が ORGANIZATION スコープで送信していることを確認
    expect(body.scopeType).toBe('ORGANIZATION')
  })
})
