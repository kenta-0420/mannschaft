import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// CMP-260930-1932 実機E2E 補強: 申込操作を画面のボタン操作で行う（API代替しない）。
// E2E-1: クレジット枯渇組織の募集（id は env で指定）に e2e-user が画面から申込み、
// クレジット不足エラーが出ずに申込が成功することを画面で確認する。

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(180_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const LISTING_ID = process.env.CMP1932_UI_LISTING_ID ?? '90286'

async function loginForRealDevice(page: Page, email: string) {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
  const pageHost = new URL(process.env.BASE_URL ?? 'http://localhost:3001').hostname
  const apiHost = new URL(API_BASE).hostname
  if (pageHost === apiHost) return
  const apiCookies = await page.context().cookies(API_BASE)
  await page.context().addCookies(apiCookies.map(cookie => ({ ...cookie, domain: pageHost })))
}

async function waitForPageHydration(page: Page) {
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 150_000 },
  )
}

test('E2E-1: 画面の申込ボタンから申込み、クレジット不足エラーが出ず成功表示になる', async ({ page }) => {
  await loginForRealDevice(page, MEMBER)
  await page.goto(`/recruitment-listings/${LISTING_ID}`, { waitUntil: 'commit' })
  await waitForPageHydration(page)

  const applyButton = page.getByRole('button', { name: '申込', exact: true })
  await expect(applyButton).toBeVisible({ timeout: 15_000 })

  const applyResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/v1/recruitment-listings/${LISTING_ID}/applications`,
  )
  await applyButton.click()
  const response = await applyResponse
  expect(response.status(), `申込API応答: ${await response.text()}`).toBe(201)

  // クレジット不足系のエラートーストが出ていないこと、申込成功トーストが出ていることを画面で確認する
  await expect(page.getByText(/クレジット|credit/i)).toHaveCount(0)
  await expect(page.locator('.p-toast')).toContainText('申込')
})
