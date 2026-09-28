import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(600_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const TEAM_SLUG = 'fc-u-18'
const SETTINGS_PATH = `/teams/${TEAM_SLUG}/settings/confirmable-notifications`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const OUTSIDER = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'

async function loginForRealDevice(page: Page, email: string) {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
  const pageHost = new URL(process.env.BASE_URL ?? 'http://localhost:3002').hostname
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
    { timeout: 300_000 },
  )
}

test('管理者が宛先グループを画面で作成し、送信前の見込み人数を確認できる', async ({ page }) => {
  const groupName = `実機宛先_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`
  let groupId = ''
  let groupCollectionUrl = ''

  await loginForRealDevice(page, ADMIN)
  try {
    await page.goto(SETTINGS_PATH, { waitUntil: 'commit' })
    await waitForPageHydration(page)
    await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })

    await page.getByTestId('recipient-group-name').fill(groupName)
    await page.getByTestId('confirmable-target-picker').first().locator('input[type="checkbox"]').first().check()
    const createdResponse = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && /\/api\/v1\/teams\/\d+\/confirmable-recipient-groups$/.test(new URL(response.url()).pathname),
    )
    await page.getByTestId('recipient-group-save').click()
    const response = await createdResponse
    const responseBody = (await response.json()) as { data: { id: string } }
    expect(response.status(), `宛先グループ作成: ${JSON.stringify(responseBody)}`).toBe(201)
    groupId = responseBody.data.id
    groupCollectionUrl = response.url()
    await expect(page.getByText(groupName, { exact: true })).toBeVisible()

    const editedGroupName = `${groupName}_edited`
    const groupRow = page.getByRole('row').filter({ hasText: groupName })
    await groupRow.getByRole('button').first().click()
    await page.getByTestId('recipient-group-name').fill(editedGroupName)
    const updatedResponse = page.waitForResponse(candidate =>
      candidate.request().method() === 'PUT'
      && new URL(candidate.url()).pathname === `${new URL(groupCollectionUrl).pathname}/${groupId}`,
    )
    await page.getByTestId('recipient-group-save').click()
    const update = await updatedResponse
    expect(update.status(), `宛先グループ更新: ${await update.text()}`).toBe(200)
    await expect(page.getByText(editedGroupName, { exact: true })).toBeVisible()

    await page.getByTestId('sender-audience-mode-select').click()
    await page.getByRole('option', { name: '保存済みグループ', exact: true }).click()
    await page.getByTestId('sender-group-select').click()
    await page.getByRole('option', { name: editedGroupName, exact: true }).click()

    await expect(page.getByText(/見込み受信者: [1-9]\d*人/)).toBeVisible({ timeout: 30_000 })
  } finally {
    if (groupId && groupCollectionUrl) {
      const deleted = await page.request.delete(`${groupCollectionUrl}/${groupId}`)
      expect(deleted.status(), `宛先グループ ${groupId} の後始末`).toBeLessThan(300)
    }
  }
})

test('一般メンバーは実画面から通知一覧を開ける', async ({ page }) => {
  await loginForRealDevice(page, MEMBER)
  const notificationsResponse = page.waitForResponse(response =>
    response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/notifications',
  )

  await page.goto('/notifications', { waitUntil: 'commit' })
  await waitForPageHydration(page)
  const response = await notificationsResponse

  expect(response.status(), `通知一覧API: ${await response.text()}`).toBe(200)
  await expect(page).toHaveURL(/\/notifications$/)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
})

for (const [label, email] of [['一般メンバー', MEMBER], ['他テナント利用者', OUTSIDER]] as const) {
  test(`${label}には管理導線がなく、設定URLを直打ちしても管理画面を表示しない`, async ({ page }) => {
    await loginForRealDevice(page, email)
    await page.goto(`/teams/${TEAM_SLUG}`, { waitUntil: 'commit' })
    await waitForPageHydration(page)
    await expect(page.locator(`a[href="${SETTINGS_PATH}"]`)).toHaveCount(0)

    await page.goto(SETTINGS_PATH, { waitUntil: 'commit' })
    await waitForPageHydration(page)
    await expect(page.getByRole('heading', { name: '確認通知設定' })).toHaveCount(0)
  })
}
