import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test('ブログ新規作成→エディタ遷移 デバッグ', async ({ page }) => {
  const consoleErrors: string[] = []
  const networkRequests: { method: string; url: string; status?: number }[] = []

  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  page.on('pageerror', (err) => consoleErrors.push(`[pageerror] ${err.message}`))

  // blog/posts API: 作成成功を返す
  await page.route('**/api/v1/blog/posts', async (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      console.log('[mock] POST /api/v1/blog/posts body:', JSON.stringify(body))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 99,
            slug: 'test-post',
            title: body?.title ?? 'テスト記事',
            body: null,
            excerpt: null,
            coverImageUrl: null,
            status: 'DRAFT',
            scopeType: body?.scopeType ?? 'PERSONAL',
            scopeId: body?.scopeId ?? null,
            author: { id: 1, displayName: 'テストユーザー', avatarUrl: null },
            tags: [],
            seriesId: null,
            seriesName: null,
            seriesOrder: null,
            publishedAt: null,
            scheduledAt: null,
            viewCount: 0,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        }),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], meta: {} }),
      })
    }
  })

  // dashboard の他APIをモック（500エラー回避）
  await page.route('**/api/v1/dashboard/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/users/me/blog/**', async (route) => {
    if (route.request().method() === 'POST') {
      const reqBody = route.request().postDataJSON()
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 99,
            title: reqBody?.title ?? 'テスト記事',
            body: reqBody?.body ?? '.',
            status: 'DRAFT',
            slug: 'test-slug',
            tags: [],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        }),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    }
  })
  await page.route('**/api/v1/teams/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/organizations/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  page.on('request', (req) => {
    if (req.url().includes('api')) {
      networkRequests.push({ method: req.method(), url: req.url() })
    }
  })
  page.on('response', (res) => {
    const entry = networkRequests.find((r) => r.url === res.url() && !r.status)
    if (entry) entry.status = res.status()
  })

  await page.goto('/dashboard')
  await waitForHydration(page)
  await page.waitForTimeout(2000)

  // マイブログウィジェットの「新規作成」ボタンを探す
  const createBtn = page.getByRole('button', { name: '新規作成' })
  const btnCount = await createBtn.count()
  console.log('[debug] 新規作成ボタン数:', btnCount)

  if (btnCount === 0) {
    console.log(
      '[debug] ウィジェットが見当たらない。ページ内テキスト:',
      await page
        .locator('body')
        .innerText()
        .then((t) => t.substring(0, 500)),
    )
    await page.screenshot({ path: 'test-results/blog-debug-no-btn.png', fullPage: true })
    expect(btnCount, '新規作成ボタンが存在しない').toBeGreaterThan(0)
    return
  }

  await createBtn.first().click()
  await page.waitForTimeout(500)

  // ダイアログが開いているか
  const dialog = page.locator('[role="dialog"]')
  const dialogVisible = await dialog.isVisible()
  console.log('[debug] ダイアログ表示:', dialogVisible)

  if (!dialogVisible) {
    await page.screenshot({ path: 'test-results/blog-debug-no-dialog.png', fullPage: true })
    expect(dialogVisible, 'ダイアログが開かない').toBe(true)
    return
  }

  await page.screenshot({ path: 'test-results/blog-debug-dialog.png', fullPage: true })

  // タイトル入力
  const titleInput = dialog.locator('input').first()
  await titleInput.click()
  await titleInput.pressSequentially('テスト記事タイトル', { delay: 30 })
  console.log('[debug] タイトル入力完了:', await titleInput.inputValue())

  // 作成ボタンクリック
  const submitBtn = dialog.getByRole('button', { name: '作成' })
  console.log('[debug] 作成ボタン disabled:', await submitBtn.isDisabled())
  await submitBtn.click()

  // 遷移を待つ
  await page.waitForTimeout(3000)

  const finalUrl = page.url()
  console.log('[debug] 最終URL:', finalUrl)
  console.log('[debug] コンソールエラー:', consoleErrors)
  console.log(
    '[debug] ネットワークリクエスト:',
    JSON.stringify(
      networkRequests.filter((r) => r.url.includes('blog')),
      null,
      2,
    ),
  )

  await page.screenshot({ path: 'test-results/blog-debug-after-submit.png', fullPage: true })

  // /blog/posts/99/edit へ遷移しているか確認
  expect(finalUrl).toContain('/blog/posts/')
})
