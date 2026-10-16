import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/** 実 Nuxt の旧ルート・認証ガード・画面読込を通す導線回帰。API 応答だけをスタブする。 */
test.use({ storageState: { cookies: [], origins: [] } })

async function authenticate(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({ id: 10, email: 'sessions@example.test', fullName: 'セッション確認' }),
    )
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3_600_000))
  })
}

async function stubApis(page: Page, sessionsStatus = 200): Promise<{ sessions: number }> {
  const calls = { sessions: 0 }
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/v1/auth/sessions') calls.sessions++
    const status = pathname === '/api/v1/auth/sessions' ? sessionsStatus : 200
    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(
        status === 200
          ? { data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } }
          : { error: { code: 'COMMON_999', message: '取得失敗' } },
      ),
    })
  })
  return calls
}

test('旧 URL は query/hash に依存せず正規画面へ転送し、空一覧を一度だけ取得する', async ({
  page,
}) => {
  await authenticate(page)
  const calls = await stubApis(page)

  await page.goto('/account/sessions?redirect=https://example.test&next=/dashboard#other-user')
  await waitForHydration(page)

  await expect(page).toHaveURL(/\/settings\/security$/)
  await expect(page.getByRole('heading', { name: 'セキュリティ', exact: true })).toBeVisible()
  await expect.poll(() => calls.sessions).toBe(1)
  await expect(page.getByText('セッション情報がありません', { exact: true })).toBeVisible()
  expect(calls.sessions).toBe(1)
})

test('旧 URL の転送は履歴を置換する', async ({ page }) => {
  await authenticate(page)
  await stubApis(page)
  await page.goto('/settings/security?previous=1')
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'セキュリティ', exact: true })).toBeVisible()

  await page.goto('/account/sessions')
  await waitForHydration(page)
  await expect(page).toHaveURL(/\/settings\/security$/)
  await expect(page.getByRole('heading', { name: 'セキュリティ', exact: true })).toBeVisible()
  await page.goBack()

  await expect(page).toHaveURL(/\/settings\/security\?previous=1$/)
})

test('未認証の旧 URL はログインへ誘導し、セッション一覧を取得しない', async ({ page }) => {
  const calls = await stubApis(page)
  await page.goto('/account/sessions?redirect=https://example.test#fragment')
  await waitForHydration(page)

  await expect(page).toHaveURL(/\/login\?/)
  const loginUrl = new URL(page.url())
  expect(loginUrl.searchParams.get('redirect')).toBe(
    '/account/sessions?redirect=https://example.test#fragment',
  )
  expect(calls.sessions).toBe(0)
})

test('一覧取得失敗でも正規画面に留まり、エラーを表示する', async ({ page }) => {
  await authenticate(page)
  const calls = await stubApis(page, 500)
  await page.goto('/account/sessions')
  await waitForHydration(page)

  await expect(page).toHaveURL(/\/settings\/security$/)
  await expect(page.getByRole('heading', { name: 'セキュリティ', exact: true })).toBeVisible()
  await expect(
    page.getByText('セキュリティ情報の取得に失敗しました', { exact: true }),
  ).toBeVisible()
  expect(calls.sessions).toBe(1)
})
