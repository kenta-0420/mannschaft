import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const email = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8082'

test.use({ storageState: { cookies: [], origins: [] } })

test('不正形式のメールではパスキー開始APIを呼ばずログイン画面に留まる', async ({ page }) => {
  const beginRequests: string[] = []
  page.on('request', request => {
    if (request.url().includes('/api/v1/auth/webauthn/login/begin')) beginRequests.push(request.url())
  })
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const button of await page.locator('#login-form button').all()) {
      const bounds = await button.boundingBox()
      expect(bounds?.height, `${width}px幅の${await button.innerText()}の高さ`).toBeGreaterThanOrEqual(44)
    }
  }
  const emailInput = page.locator('#email')
  await emailInput.fill('not-an-email')

  await page.getByRole('button', { name: 'パスキーでログイン', exact: true }).click()

  await expect(emailInput).toBeFocused()
  expect(await emailInput.evaluate(input => (input as HTMLInputElement).validity.typeMismatch)).toBe(true)
  expect(beginRequests).toHaveLength(0)
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('[data-passkey-login]')).toBeEnabled()
})

test('パスキーを設定画面で登録しログアウト後のログイン画面からログインして再読込できる', async ({ page }) => {
  test.setTimeout(180_000)
  const client = await page.context().newCDPSession(page)
  await client.send('WebAuthn.enable', { enableUI: false })
  await client.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
    hasUserVerification: true, isUserVerified: true,
  } })
  const deviceName = `passkey-login-ui-${crypto.randomUUID()}`
  let registrationAttempted = false
  let registrationConfirmed = false
  try {
    await page.goto('/login?redirect=/settings/security', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('#email').fill(email)
    await page.locator('#password').fill(password)
    await page.locator('[data-login-submit]').click()
    await expect(page).toHaveURL(/\/settings\/security$/, { timeout: 60_000 })

    await page.getByRole('button', { name: 'パスキーを登録', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'パスキーを登録' })
    await expect(dialog).toBeVisible()
    await dialog.locator('input').fill(deviceName)
    const registered = page.waitForResponse(response => response.url().includes('/webauthn/register/complete'))
    registrationAttempted = true
    await dialog.getByRole('button', { name: '登録する', exact: true }).click()
    expect((await registered).ok()).toBe(true)
    registrationConfirmed = true
    await expect(dialog).not.toBeVisible()
    await expect(page.getByText(deviceName, { exact: true })).toBeVisible()

    await page.locator('header button').filter({ has: page.locator('.pi-sign-out') }).first().click()
    await expect(page).toHaveURL(/\/login$/, { timeout: 30_000 })
    await page.goto('/login?redirect=/settings/security', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('#email').fill(email)
    await expect(page.locator('#password')).toHaveValue('')
    const begin = page.waitForResponse(response => response.url().includes('/webauthn/login/begin'))
    const complete = page.waitForResponse(response => response.url().includes('/webauthn/login/complete'))
    await page.getByRole('button', { name: 'パスキーでログイン', exact: true }).click()
    const beginResponse = await begin
    expect(beginResponse.ok()).toBe(true)
    expect(new URL(beginResponse.url()).searchParams.get('email')).toBe(email)
    expect((await complete).ok()).toBe(true)
    await expect(page).toHaveURL(/\/settings\/security$/, { timeout: 60_000 })
    await expect(page.getByText(deviceName, { exact: true })).toBeVisible()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page).toHaveURL(/\/settings\/security$/)
    await expect(page.getByText(deviceName, { exact: true })).toBeVisible({ timeout: 30_000 })
  } finally {
    try {
      // この試験で作った資格情報だけを削除し、既存のパスキーを保全する。
      if (registrationAttempted) {
        let response = await page.request.get(`${apiBase}/api/v1/auth/webauthn/credentials`)
        if (response.status() === 401 || response.status() === 403) {
          // 後始末用の再認証。検証対象のログイン導線にはAPIを代用しない。
          const login = await page.request.post(`${apiBase}/api/v1/auth/login`, { data: { email, password } })
          expect(login.ok(), '試験用パスキーの後始末に必要な再認証').toBe(true)
          response = await page.request.get(`${apiBase}/api/v1/auth/webauthn/credentials`)
        }
        expect(response.ok(), '後始末対象の資格情報一覧を取得できること').toBe(true)
        const credentials = (await response.json()).data as { id: number; deviceName: string }[]
        const fixtures = credentials.filter(value => value.deviceName === deviceName)
        if (registrationConfirmed) expect(fixtures).toHaveLength(1)
        for (const credential of fixtures) {
          const deleted = await page.request.delete(`${apiBase}/api/v1/auth/webauthn/credentials/${credential.id}`)
          expect(deleted.ok(), '試験用パスキーの削除').toBe(true)
        }
        const remaining = await page.request.get(`${apiBase}/api/v1/auth/webauthn/credentials`)
        expect(remaining.ok(), '試験用パスキー削除後の一覧取得').toBe(true)
        expect(((await remaining.json()).data as { deviceName: string }[]).some(value => value.deviceName === deviceName)).toBe(false)
      }
    } finally {
      await client.detach()
    }
  }
})
