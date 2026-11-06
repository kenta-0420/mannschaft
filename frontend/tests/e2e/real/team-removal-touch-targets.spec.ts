import { test, expect, errors, type Locator } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/** 本人ADMINの専用ACTIVE加盟を明示指定し、確認だけを実DOMで測る。既存加盟は変更しない。 */
const orgSlug = process.env.TOUCH_REMOVE_ORG_SLUG
const teamSlug = process.env.TOUCH_REMOVE_TEAM_SLUG
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8080'
test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 360, height: 800 }, hasTouch: true, isMobile: true })
test.setTimeout(180_000)

test('360px: 除名確認の確定・キャンセル・閉じるは44x44以上で、キャンセルは加盟を保持する', async ({ page }) => {
  test.skip(!orgSlug || !teamSlug, '本人ADMINの専用ACTIVE加盟fixtureのslugを明示する')
  await loginViaApi(page, {
    email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
    password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
  }, { apiBaseUrl: apiBase })
  const orgPath = `${apiBase}/api/v1/organizations/${orgSlug}`
  const permissions = await page.request.get(`${orgPath}/me/permissions`)
  expect(permissions.status()).toBe(200)
  expect((await permissions.json()).data).toMatchObject({ scopeAdmin: true, roleName: 'ADMIN' })
  const before = await page.request.get(`${orgPath}/teams`)
  expect(before.status()).toBe(200)
  expect((await before.json()).data).toEqual(expect.arrayContaining([expect.objectContaining({ slug: teamSlug })]))
  const mutations: string[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname === `/api/v1/organizations/${orgSlug}/teams/${teamSlug}`
      && request.method() !== 'GET') mutations.push(request.method())
  })

  async function postponePermissions() {
    const postpone = page.getByRole('button', { name: 'あとで決める', exact: true })
    try {
      await postpone.waitFor({ state: 'visible', timeout: 5_000 })
      await postpone.tap()
      await expect(postpone).toBeHidden()
    }
    catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error
    }
  }
  async function measure(target: Locator, label: string) {
    await expect(target).toBeVisible()
    await expect.poll(() => target.evaluate((element) => {
      let current: Element | null = element
      while (current) {
        if (current.getAnimations().some(animation => animation.effect?.getTiming().iterations !== Infinity
          && animation.playState !== 'finished' && animation.playState !== 'idle')) return false
        current = current.parentElement
      }
      return true
    })).toBe(true)
    const box = await target.boundingBox()
    expect(box).not.toBeNull()
    await test.info().attach(label, { body: JSON.stringify(box), contentType: 'application/json' })
    expect.soft(box!.width, `${label}の操作領域幅`).toBeGreaterThanOrEqual(44)
    expect.soft(box!.height, `${label}の操作領域高さ`).toBeGreaterThanOrEqual(44)
  }

  await page.goto(`/organizations/${orgSlug}`)
  await waitForHydration(page)
  await postponePermissions()
  const admin = page.getByRole('button', { name: '管理者', exact: true })
  await expect(admin).toBeVisible({ timeout: 30_000 })
  if (await admin.getAttribute('aria-pressed') !== 'true') await admin.tap()
  await page.goto(`/organizations/${orgSlug}/member-teams`)
  await waitForHydration(page)
  await postponePermissions()
  const remove = page.getByTestId(`team-remove-${teamSlug}`)
  await expect(remove).toBeVisible({ timeout: 60_000 })
  await remove.tap()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toHaveCount(1)
  await measure(dialog.getByRole('button', { name: '除名', exact: true }), '除名-確定')
  await measure(dialog.getByRole('button', { name: 'キャンセル', exact: true }), '除名-キャンセル')
  await measure(dialog.locator('button[data-pc-name="pcclosebutton"]'), '除名-閉じる')
  await test.info().attach('除名確認360px', { body: await page.screenshot({ animations: 'disabled' }), contentType: 'image/png' })
  await dialog.getByRole('button', { name: 'キャンセル', exact: true }).tap()
  await expect(dialog).toBeHidden()
  await expect(remove).toBeVisible()
  const after = await page.request.get(`${orgPath}/teams`)
  expect(after.status()).toBe(200)
  expect((await after.json()).data).toEqual(expect.arrayContaining([expect.objectContaining({ slug: teamSlug })]))
  expect(mutations, 'キャンセル・寸法測定では加盟を変更しない').toEqual([])
  await test.info().attach('加盟保持', { body: JSON.stringify({ orgSlug, teamSlug, mutations }), contentType: 'application/json' })
})
