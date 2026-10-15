import { expect, test, type Page } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(300_000)
test.describe.configure({ mode: 'serial' })

const ADMIN_CREDS = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const MEMBER_CREDS = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const OTHER_VILLAGE_ID = '7f000101-9fcf-11d8-819f-cf38ad970012'
const SCREENSHOT_DIR = 'test-results/alicization/cmp-260925-0908'

async function waitForRenderedPage(page: Page): Promise<void> {
  await page.waitForFunction(
    () => {
      const root = document.querySelector('#__nuxt')
      return root !== null && root.childElementCount > 0
    },
    undefined,
    { timeout: 90_000 },
  )
}

async function login(page: Page, credentials: { email: string; password: string }): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.locator('input#email').fill(credentials.email)
  await page.locator('input[type="password"]').fill(credentials.password)

  const response = page.waitForResponse(
    candidate => candidate.url().includes('/api/v1/auth/login'),
    { timeout: 90_000 },
  )
  const navigation = page.waitForURL(url => !url.pathname.includes('/login'), {
    timeout: 90_000,
    waitUntil: 'domcontentloaded',
  })
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  expect((await response).status()).toBe(200)
  await navigation
  await waitForRenderedPage(page)
}

test('居住者1: 管理担当者が必要な一覧へ到達し、通常状態を誤解なく確認できる', async ({ page }) => {
  await login(page, ADMIN_CREDS)
  await page.goto('/system-admin/batches', { waitUntil: 'commit' })
  await waitForRenderedPage(page)

  await expect(page.getByTestId('batches-error-state')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'バッチ管理', exact: true })).toBeVisible({
    timeout: 90_000,
  })

  await page.screenshot({
    path: `${SCREENSHOT_DIR}/resident-1-admin-straightforward.png`,
    fullPage: true,
  })
})

test('居住者2: ITに不慣れな一般会員が保存済み管理URLを開き、次の行動を理解できる', async ({ page }) => {
  await login(page, MEMBER_CREDS)
  await page.goto('/system-admin/batches', { waitUntil: 'commit' })
  await waitForRenderedPage(page)

  const errorState = page.getByTestId('batches-error-state')
  await expect(errorState).toBeVisible({ timeout: 90_000 })
  await expect(errorState).toContainText('この画面をご利用いただけません')
  await expect(errorState).toContainText('必要な場合は管理者へご確認ください')
  await expect(errorState).not.toContainText(/403|Forbidden|HTTP|権限エラー/i)
  await expect(page.getByTestId('batches-error-state-retry')).toHaveCount(0)

  await page.screenshot({
    path: `${SCREENSHOT_DIR}/resident-2-member-low-literacy.png`,
    fullPage: true,
  })
})

test('居住者3: スマホ利用者が他村カレンダーを開いても案内が崩れず読める', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page, MEMBER_CREDS)
  await page.goto(`/villages/${OTHER_VILLAGE_ID}/calendar`, { waitUntil: 'commit' })
  await waitForRenderedPage(page)

  const errorState = page.getByTestId('village-calendar-error-state')
  await expect(errorState).toBeVisible({ timeout: 90_000 })
  await expect(errorState).toContainText('この画面をご利用いただけません')
  await expect(page.getByTestId('village-calendar-error-state-retry')).toHaveCount(0)
  const errorToast = page.locator('.p-toast-message').filter({ hasText: 'この操作を行う権限がありません' })
  await expect(errorToast).toBeVisible({ timeout: 90_000 })
  const toastBox = await errorToast.boundingBox()
  expect(toastBox, 'エラー通知の表示領域を取得できること').not.toBeNull()
  expect(toastBox!.x, 'エラー通知の左端が画面内に収まること').toBeGreaterThanOrEqual(0)
  expect(toastBox!.x + toastBox!.width, 'エラー通知の右端が画面内に収まること').toBeLessThanOrEqual(390)
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
    'スマホ幅で横スクロールが発生した',
  ).toBeTruthy()

  await page.screenshot({
    path: `${SCREENSHOT_DIR}/resident-3-mobile-display.png`,
    fullPage: true,
  })
})
