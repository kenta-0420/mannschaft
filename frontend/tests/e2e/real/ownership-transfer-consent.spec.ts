import {
  expect,
  request,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { execSync } from 'child_process'
import { NobleCryptoPlugin, ScureBase32Plugin, TOTP } from 'otplib'
import { waitForHydration } from '../helpers/wait'

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BROWSER_API_BASE = process.env.BROWSER_API_BASE_URL ?? API_BASE
const PASSWORD = 'TestPass2026!'
const ISSUER = { email: 'f087-outsider@test.mannschaft.local', password: PASSWORD }
const TARGET = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }
const ISSUER_ID = 90117
const TARGET_ID = 90245

interface TeamFixture {
  slug: string
  name: string
}

function cleanupTestUsersTwoFactorAuth(): void {
  execSync(
    `wsl.exe -e docker exec mannschaft-mysql mysql -uroot -proot mannschaft -e "DELETE FROM two_factor_auth WHERE user_id IN (${ISSUER_ID},${TARGET_ID});"`,
    { stdio: 'pipe' },
  )
}

function disableTargetTwoFactorAuth(): void {
  execSync(
    `wsl.exe -e docker exec mannschaft-mysql mysql -uroot -proot mannschaft -e "DELETE FROM two_factor_auth WHERE user_id = ${TARGET_ID};"`,
    { stdio: 'pipe' },
  )
}

function createTotp(secret: string): TOTP {
  return new TOTP({
    secret,
    crypto: new NobleCryptoPlugin(),
    base32: new ScureBase32Plugin(),
  })
}

async function loginApi(api: APIRequestContext, credentials: typeof ISSUER): Promise<void> {
  const response = await api.post('/api/v1/auth/login', { data: credentials })
  expect(response.status(), await response.text()).toBe(200)
}

async function nextTotpCode(secret: string, previousCode: string): Promise<string> {
  const totp = createTotp(secret)
  await expect.poll(() => totp.generate(), {
    message: '2FA有効化に使ったコードと異なる時間窓へ進むこと',
    timeout: 35_000,
    intervals: [500],
  }).not.toBe(previousCode)
  return totp.generate()
}

async function loginApiViaTotp(
  api: APIRequestContext,
  credentials: typeof ISSUER,
  secret: string,
  previousCode: string,
): Promise<string> {
  const loginResponse = await api.post('/api/v1/auth/login', { data: credentials })
  expect(loginResponse.status(), await loginResponse.text()).toBe(200)
  const loginBody = await loginResponse.json() as { data: { mfaSessionToken: string } }
  expect(loginBody.data.mfaSessionToken).toBeTruthy()
  const totpCode = await nextTotpCode(secret, previousCode)
  const validateResponse = await api.post('/api/v1/auth/2fa/validate', {
    data: { mfaSessionToken: loginBody.data.mfaSessionToken, totpCode },
  })
  expect(validateResponse.status(), await validateResponse.text()).toBe(200)
  const validateBody = await validateResponse.json() as { data: { accessToken: string } }
  expect(validateBody.data.accessToken).toBeTruthy()
  return validateBody.data.accessToken
}

async function loginViaTotp(
  page: Page,
  credentials: typeof ISSUER,
  secret: string,
  previousCode: string,
): Promise<string> {
  await page.context().clearCookies()

  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('input#email').fill(credentials.email)
  await page.locator('input[type="password"]').fill(credentials.password)
  const loginResponsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/auth/login'),
  )
  await page.locator('form button[type="submit"]').click()
  const loginResponse = await loginResponsePromise
  expect(loginResponse.status(), await loginResponse.text()).toBe(200)
  const loginBody = await loginResponse.json() as { data: { mfaRequired?: boolean } }
  expect(loginBody.data.mfaRequired).toBe(true)
  await expect(page).toHaveURL(/\/2fa-verify/, { timeout: 60_000 })
  await waitForHydration(page)

  const totpCode = await nextTotpCode(secret, previousCode)
  const otpInputs = page.locator('.p-inputotp input')
  await expect(otpInputs).toHaveCount(6)
  for (let i = 0; i < 6; i++) {
    await otpInputs.nth(i).fill(totpCode[i]!)
  }
  const verifyButton = page.locator('form button[type="submit"]')
  await expect(verifyButton).toBeEnabled()
  await verifyButton.click()
  await expect(page).not.toHaveURL(/\/2fa-verify/, { timeout: 60_000 })
  await expect.poll(() => page.evaluate(() => localStorage.getItem('currentUser')))
    .not.toBeNull()
  return totpCode
}

async function loginViaPassword(
  page: Page,
  credentials: typeof ISSUER,
): Promise<void> {
  await page.context().clearCookies()
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('input#email').fill(credentials.email)
  await page.locator('input[type="password"]').fill(credentials.password)
  const loginResponsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
    && response.url().endsWith('/api/v1/auth/login'),
  )
  await page.locator('form button[type="submit"]').click()
  const loginResponse = await loginResponsePromise
  expect(loginResponse.status(), await loginResponse.text()).toBe(200)
  const loginBody = await loginResponse.json() as { data: { mfaRequired?: boolean } }
  expect(loginBody.data.mfaRequired).not.toBe(true)
  await expect(page).not.toHaveURL(/\/login/, { timeout: 60_000 })
  await expect.poll(() => page.evaluate(() => localStorage.getItem('currentUser')))
    .not.toBeNull()
}

async function acceptConfirmDialog(page: Page): Promise<void> {
  const dialog = page.locator('.p-confirmdialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  await dialog.getByRole('button', { name: 'はい', exact: true }).click()
}

test.describe('F01.2 所有権移譲の承諾 実機E2E', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(300_000)

  let issuerApi: APIRequestContext | undefined
  let targetApi: APIRequestContext | undefined
  let cleanupApi: APIRequestContext | undefined
  let team: TeamFixture | undefined
  let issuerTotpSecret = ''
  let issuerSetupTotpCode = ''
  let issuerLastTotpCode = ''
  let targetTotpSecret = ''
  let targetSetupTotpCode = ''
  let targetLastTotpCode = ''
  let targetDisplayName = ''
  let ownershipAccepted = false

  test.beforeAll(async () => {
    cleanupTestUsersTwoFactorAuth()
    issuerApi = await request.newContext({ baseURL: API_BASE })
    targetApi = await request.newContext({ baseURL: API_BASE })
    await loginApi(issuerApi, ISSUER)
    await loginApi(targetApi, TARGET)

    const suffix = Date.now()
    const name = `E2E所有権移譲${suffix}`
    const createTeam = await issuerApi.post('/api/v1/teams', {
      data: {
        name,
        slug: `e2e-owner-${suffix}`.slice(0, 30),
        visibility: 'MEMBERS_AND_ABOVE',
      },
    })
    expect(createTeam.status(), await createTeam.text()).toBe(201)
    const teamData = (await createTeam.json()).data as { slug: string }
    team = { slug: teamData.slug, name }

    const invite = await issuerApi.post(`/api/v1/teams/${team.slug}/invite-tokens`, {
      data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
    })
    expect(invite.status(), await invite.text()).toBe(201)
    const inviteData = (await invite.json()).data as { token: string }
    const join = await targetApi.post(`/api/v1/invite/${inviteData.token}/join`)
    expect(join.status(), await join.text()).toBe(200)

    const members = await issuerApi.get(`/api/v1/teams/${team.slug}/members?page=0&size=100`)
    expect(members.status(), await members.text()).toBe(200)
    const memberData = (await members.json()).data as Array<{
      userId: number
      displayName: string
    }>
    targetDisplayName = memberData.find(member => member.userId === TARGET_ID)?.displayName ?? ''
    expect(targetDisplayName).toBeTruthy()
  })

  test.afterAll(async () => {
    try {
      if (team) {
        cleanupApi = await request.newContext({ baseURL: API_BASE })
        let cleanupAccessToken: string | undefined
        if (ownershipAccepted) {
          cleanupAccessToken = await loginApiViaTotp(
            cleanupApi,
            TARGET,
            targetTotpSecret,
            targetLastTotpCode || targetSetupTotpCode,
          )
        }
        else if (issuerTotpSecret) {
          cleanupAccessToken = await loginApiViaTotp(
            cleanupApi,
            ISSUER,
            issuerTotpSecret,
            issuerLastTotpCode || issuerSetupTotpCode,
          )
        }
        else {
          await loginApi(cleanupApi, ISSUER)
        }
        const deleted = await cleanupApi.delete(`/api/v1/teams/${team.slug}`, cleanupAccessToken
          ? { headers: { Authorization: `Bearer ${cleanupAccessToken}` } }
          : {})
        expect(deleted.status(), await deleted.text()).toBe(204)
      }
    }
    finally {
      cleanupTestUsersTwoFactorAuth()
      await cleanupApi?.dispose()
      await targetApi?.dispose()
      await issuerApi?.dispose()
    }
  })

  test('OWNER-REAL-001: 打診の永続化、2FA必須、承諾後のADMIN/MEMBER反転を一貫して確認する', async ({ page }) => {
    expect(team).toBeDefined()

    const targetPrerequisiteSetup = await targetApi!.post('/api/v1/auth/2fa/setup')
    expect(targetPrerequisiteSetup.status(), await targetPrerequisiteSetup.text()).toBe(201)
    targetTotpSecret = ((await targetPrerequisiteSetup.json()).data as { secret: string }).secret
    targetSetupTotpCode = await createTotp(targetTotpSecret).generate()
    const targetPrerequisiteVerify = await targetApi!.post('/api/v1/auth/2fa/verify', {
      data: { totpCode: targetSetupTotpCode },
    })
    expect(targetPrerequisiteVerify.status(), await targetPrerequisiteVerify.text()).toBe(200)

    const issuerSetup = await issuerApi!.post('/api/v1/auth/2fa/setup')
    expect(issuerSetup.status(), await issuerSetup.text()).toBe(201)
    issuerTotpSecret = ((await issuerSetup.json()).data as { secret: string }).secret
    issuerSetupTotpCode = await createTotp(issuerTotpSecret).generate()
    const issuerVerify = await issuerApi!.post('/api/v1/auth/2fa/verify', {
      data: { totpCode: issuerSetupTotpCode },
    })
    expect(issuerVerify.status(), await issuerVerify.text()).toBe(200)

    issuerLastTotpCode = await loginViaTotp(
      page,
      ISSUER,
      issuerTotpSecret,
      issuerSetupTotpCode,
    )
    await page.goto(`/teams/${team!.slug}/members`)
    await waitForHydration(page)

    await page.getByTestId('transfer-ownership-open').click()
    await expect(page.getByTestId('transfer-ownership-dialog')).toBeVisible()
    await page.getByTestId('transfer-ownership-target').click()
    await page.locator('.p-select-option').filter({ hasText: targetDisplayName }).click()

    const submit = page.getByTestId('transfer-ownership-submit')
    await expect(submit).toBeDisabled()
    await page.getByTestId('transfer-ownership-confirm').fill(team!.name)
    await expect(submit).toBeEnabled()

    const createOfferPromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/teams/${team!.slug}/transfer-ownership-offers`),
    )
    await submit.click()
    const createOffer = await createOfferPromise
    expect(createOffer.status(), await createOffer.text()).toBe(201)
    const offerId = ((await createOffer.json()).data as { offerId: string }).offerId
    expect(offerId).toBeTruthy()
    await expect(page.getByTestId('transfer-ownership-pending')).toContainText(targetDisplayName)

    await page.reload()
    await waitForHydration(page)
    await expect(page.getByTestId('transfer-ownership-pending')).toContainText(targetDisplayName)

    disableTargetTwoFactorAuth()
    targetTotpSecret = ''
    targetSetupTotpCode = ''
    targetLastTotpCode = ''
    await loginViaPassword(page, TARGET)
    await page.goto(`/teams/${team!.slug}/members?offerId=${offerId}`)
    await waitForHydration(page)
    await expect(page.getByText(`${team!.name} の管理者への就任を打診されています`))
      .toBeVisible({ timeout: 15_000 })

    const rejectedAcceptPromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().endsWith(`/transfer-ownership-offers/${offerId}/accept`),
    )
    await page.getByRole('button', { name: '引き受ける', exact: true }).click()
    await acceptConfirmDialog(page)
    const rejectedAccept = await rejectedAcceptPromise
    expect(rejectedAccept.status(), await rejectedAccept.text()).toBe(422)
    const rejectedBody = await rejectedAccept.json() as { error: { code: string } }
    expect(rejectedBody.error.code).toBe('ROLE_010')
    await expect(page.getByText('管理者になるには2段階認証の設定が必要です')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`offerId=${offerId}`))

    await page.getByRole('button', { name: '2段階認証を設定する', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/security$/)

    const setup = await targetApi!.post('/api/v1/auth/2fa/setup')
    expect(setup.status(), await setup.text()).toBe(201)
    targetTotpSecret = ((await setup.json()).data as { secret: string }).secret
    targetSetupTotpCode = await createTotp(targetTotpSecret).generate()
    const verify = await targetApi!.post('/api/v1/auth/2fa/verify', {
      data: { totpCode: targetSetupTotpCode },
    })
    expect(verify.status(), await verify.text()).toBe(200)

    targetLastTotpCode = await loginViaTotp(
      page,
      TARGET,
      targetTotpSecret,
      targetSetupTotpCode,
    )
    await page.goto(`/teams/${team!.slug}/members?offerId=${offerId}`)
    await waitForHydration(page)

    const acceptedPromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
      && response.url().endsWith(`/transfer-ownership-offers/${offerId}/accept`),
    )
    await page.getByRole('button', { name: '引き受ける', exact: true }).click()
    await acceptConfirmDialog(page)
    const accepted = await acceptedPromise
    expect(accepted.status(), await accepted.text()).toBe(200)
    ownershipAccepted = true
    await expect(page.getByText('管理者を引き継ぎました')).toBeVisible()
    await expect(page).not.toHaveURL(/offerId=/)

    await page.reload()
    await waitForHydration(page)
    const members = await page.request.get(
      `${BROWSER_API_BASE}/api/v1/teams/${team!.slug}/members?page=0&size=100`,
    )
    expect(members.status(), await members.text()).toBe(200)
    const memberData = (await members.json()).data as Array<{ userId: number; roleName: string }>
    expect(memberData.find(member => member.userId === TARGET_ID)?.roleName).toBe('ADMIN')
    expect(memberData.find(member => member.userId === ISSUER_ID)?.roleName).toBe('MEMBER')
    await expect(page.getByTestId('transfer-ownership-open')).toBeVisible()
  })
})
