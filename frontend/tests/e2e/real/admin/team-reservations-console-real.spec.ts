import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

/**
 * CMP-261017-2052: 実BE・実FEを使う予約管理の独立SPA確認。
 * API補助はログインと既存fixtureの前提確認のみ。データ作成・設定変更は行わない。
 * 専用FEは同一originの通常HTTP proxyでBEへ接続し、ブラウザの通信を横取りしない。
 */
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8080'
const teamSlug = process.env.TEST_RESERVATIONS_TEAM_SLUG ?? 'fc-u-18'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const hubPath = `/teams/${teamSlug}/admin`
const consolePath = `${hubPath}/reservations`
const oldPath = `/teams/${teamSlug}/reservations`
const adminEmail = process.env.TEST_RESERVATIONS_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(120_000)

async function login(page: Page, email: string): Promise<void> {
  await loginViaApi(page, { email, password }, { apiBaseUrl: apiBase })
  const roles = await page.request.get(`${apiBase}/api/v1/me/teams`)
  expect(roles.status(), '既存fixtureの所属ロールを実BEで確認').toBe(200)
  if (email === adminEmail) {
    const teams = (await roles.json()).data as Array<{ slug: string; role: string }>
    expect(teams.some((team) => team.slug === teamSlug && team.role === 'ADMIN')).toBe(true)
  }
}

async function expectConsole(page: Page): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`${consolePath}/?$`))
  await expect(page.getByTestId('team-admin-reservations')).toBeVisible()
  await expect(page.getByRole('heading', { name: '予約確認', exact: true })).toBeVisible()
  // 予約panel内のタブは残し、ダッシュボード全体のスコープ切替UIだけが無いこと。
  await expect(page.getByTestId('scope-carousel')).toHaveCount(0)
  await expect(page.getByTestId('scope-tab-bar-TEAM')).toHaveCount(0)
  await expect(page.locator('.scope-shell__tab')).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '予約する', exact: true })).toBeVisible()
}

async function markDocument(page: Page): Promise<void> {
  await page.evaluate(() => {
    Reflect.set(window, '__reservationSpaDocument', 'same-document')
  })
}

async function expectSameDocument(page: Page): Promise<void> {
  expect(await page.evaluate(() => Reflect.get(window, '__reservationSpaDocument'))).toBe('same-document')
}

test('ADMIN: 管理カードからSPA遷移・管理ハブへ戻る・履歴・直打ち再読込・旧タブを維持', async ({ page }) => {
  await login(page, adminEmail)
  await page.goto(hubPath)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'チーム管理コンソール', exact: true })).toBeVisible()
  await markDocument(page)
  const linesLoaded = page.waitForResponse((response) =>
    response.url().includes(`/teams/${teamSlug}/reservation-lines`) && response.request().method() === 'GET' && response.ok(),
  )
  await page.getByRole('link', { name: /予約確認/ }).click()
  await expectConsole(page)
  await linesLoaded
  await expectSameDocument(page)
  await page.screenshot({ path: 'test-results/team-reservations-console-desktop.png', fullPage: true })
  await page.getByRole('link', { name: 'チーム管理コンソール', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`${hubPath}/?$`))
  await expectSameDocument(page)
  await page.goBack()
  await expectConsole(page)
  await expectSameDocument(page)
  await page.goto(consolePath)
  await expectConsole(page)
  await page.reload()
  await expectConsole(page)
  await page.goto(oldPath)
  await waitForHydration(page)
  await expect(page).toHaveURL(new RegExp(`${oldPath}/?$`))
  await expect(page.locator('.scope-shell__tab').first()).toBeVisible()
  await expect(page.getByRole('tab', { name: '予約', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '予約する', exact: true })).toBeVisible()
})

test('ADMIN: ダッシュボード管理ウィジェットから独立予約ページへSPA遷移', async ({ page }) => {
  await login(page, adminEmail)
  await page.goto('/dashboard')
  await waitForHydration(page)
  await page.getByTestId('scope-segment-TEAM').click()
  await page.getByTestId(`scope-tab-chip-TEAM-${teamSlug}`).click()
  const adminToggle = page.getByTestId('admin-lens-toggle-TEAM')
  await expect(adminToggle).toBeVisible()
  if (await adminToggle.getAttribute('aria-pressed') !== 'true') await adminToggle.click()
  const link = page.getByTestId('admin-reservations-link')
  await expect(link).toHaveAttribute('href', consolePath)
  await markDocument(page)
  await link.click()
  await expectConsole(page)
  await expectSameDocument(page)
})

for (const [role, email, expectedRole] of [
  ['MEMBER', 'e2e-user@test.mannschaft.local', 'MEMBER'],
  ['SUPPORTER', 'e2e-supporter@test.mannschaft.local', 'SUPPORTER'],
  ['他チームADMIN', 'e2e-dummy-6@test.mannschaft.local', 'ADMIN'],
] as const) {
  test(`${role}: 独立予約ページの直打ちを拒否し、管理導線も表示しない`, async ({ page }) => {
    await login(page, email)
    const rolesResponse = await page.request.get(`${apiBase}/api/v1/me/teams`)
    const teams = (await rolesResponse.json()).data as Array<{ slug: string; role: string }>
    if (role === '他チームADMIN') {
      expect(teams.some((team) => team.slug !== teamSlug && team.role === expectedRole)).toBe(true)
      expect(teams.some((team) => team.slug === teamSlug)).toBe(false)
    } else {
      expect(teams.some((team) => team.slug === teamSlug && team.role === expectedRole)).toBe(true)
    }
    await page.goto(consolePath)
    await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/?$`))
    await expect(page.getByTestId('team-admin-reservations')).toHaveCount(0)
    await expect(page.getByRole('link', { name: /予約確認/ })).toHaveCount(0)
    await expect(page.getByTestId('admin-reservations-link')).toHaveCount(0)
    // 拒否後も実在するチーム画面が表示され、空白や汎用エラーでないこと。
    await expect(page.getByRole('tab', { name: '予約', exact: true })).toBeVisible()
  })
}
