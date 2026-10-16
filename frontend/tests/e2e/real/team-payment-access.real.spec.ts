import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { readFile, writeFile } from 'node:fs/promises'
import type { TeamResponse } from '../../../app/types/team'
import type { components } from '../../../app/types/generated'

// 実BEのログイン・専用チームの前提作成・後始末だけAPIを使い、対象閲覧はブラウザで踏む。
// 請求/Connect/精算の既存データは変更しない。正常詳細の描画はSFC/既存モックE2Eで補完する。
// 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 npx playwright test
// tests/e2e/real/team-payment-access.real.spec.ts --project chromium-real --no-deps --workers=1
// 閲覧認可の試験にPWA大量precacheやオフライン経路を混在させない。
test.use({ storageState: { cookies: [], origins: [] }, serviceWorkers: 'block' })
test.describe.configure({ mode: 'serial' })
const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const missingRequestId = '00000000-0000-4000-8000-000000000001'
type Session = { api: APIRequestContext; token: string; user: { id: number; email: string; fullName: string }; expiresAt: number }
const sessions = new Map<string, Session>()
let team: { id: number; slug: string } | undefined
let inviteId: number | undefined
const joinedRoles: string[] = []
let setupComplete = false

async function send(api: APIRequestContext, path: string, method = 'GET', data?: unknown) {
  const response = await api.fetch(`${BE}/api/v1${path}`, { method, data })
  const body = await response.text()
  expect(response.ok(), `${method} ${path}: ${response.status()}${response.ok() ? '' : ` ${body}`}`).toBe(true)
  return response
}

async function json<T>(api: APIRequestContext, path: string, method = 'GET', data?: unknown): Promise<{ data: T }> {
  const response = await send(api, path, method, data)
  return response.json()
}

async function login(key: string, email: string) {
  const password = process.env.TEST_USER_PASSWORD
  if (!password) throw new Error('TEST_USER_PASSWORD is required for local seeded test accounts')
  const loginApi = await request.newContext()
  const response = await loginApi.post(`${BE}/api/v1/auth/login`, { data: { email, password } })
  expect(response.status(), `${key}の実ログイン`).toBe(200)
  const { data: { accessToken } } = await response.json()
  const api = await request.newContext({
    extraHTTPHeaders: { Authorization: `Bearer ${accessToken}` },
    storageState: await loginApi.storageState(),
  })
  await loginApi.dispose()
  const { data: me } = await json<{ id: number; email: string; lastName: string; firstName: string }>(api, '/users/me')
  const { exp } = JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString())
  sessions.set(key, { api, token: accessToken, expiresAt: exp * 1000, user: {
    id: me.id, email: me.email, fullName: `${me.lastName} ${me.firstName}`,
  } })
}

async function open(page: Page, role: string, path: string) {
  const session = sessions.get(role)!
  const storage = await session.api.storageState()
  await page.context().addCookies(storage.cookies)
  await page.addInitScript(({ user, expiresAt }) => {
    localStorage.setItem('locale', 'ja')
    localStorage.setItem('currentUser', JSON.stringify(user))
    localStorage.setItem('tokenExpiresAt', String(expiresAt))
  }, { user: session.user, expiresAt: session.expiresAt })
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
}

test.beforeAll(async () => {
  test.setTimeout(120_000)
  await login('ADMIN', process.env.TEST_PAYMENT_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local')
  await login('DEPUTY_ADMIN', process.env.TEST_PAYMENT_DEPUTY_EMAIL ?? 'e2e-dummy-3@test.mannschaft.local')
  await login('MEMBER', process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local')
  await login('OTHER_TEAM_ADMIN', process.env.TEST_PAYMENT_OUTSIDER_EMAIL ?? 'e2e-dummy-10@test.mannschaft.local')
  await login('SUPPORTER', process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local')
  const admin = sessions.get('ADMIN')!
  if (process.env.REUSE_PAYMENT_ACCESS_FIXTURE === 'true') {
    // 再試行では同じ専用scopeの読取検証だけを行い、所属変更や後始末はしない。
    expect(process.env.KEEP_PAYMENT_ACCESS_FIXTURE).toBe('true')
    const saved: { team: { id: number; slug: string }; inviteId: number; users: Record<string, { id: number }> } = JSON.parse(
      await readFile('tests/e2e/.auth/team-payment-fixture.json', 'utf8'),
    )
    expect(Number.isSafeInteger(saved.team.id) && saved.team.id > 0).toBe(true)
    expect(saved.team.slug).toMatch(/^billingaccess-\d+$/)
    const current = (await json<TeamResponse>(admin.api, `/teams/${saved.team.slug}`)).data
    expect(current.numericId).toBe(saved.team.id)
    expect(current.slug).toBe(saved.team.slug)
    const outsiderTeams = (await json<Array<{ id: number; role: string }>>(sessions.get('OTHER_TEAM_ADMIN')!.api, '/me/teams')).data
    expect(outsiderTeams.some(scope => scope.role === 'ADMIN' && scope.id !== saved.team.id)).toBe(true)
    expect(outsiderTeams.some(scope => scope.id === saved.team.id)).toBe(false)
    for (const [role, session] of sessions) {
      expect(session.user.id).toBe(saved.users[role]?.id)
      const permissions = await json<{ roleName: string | null }>(session.api, `/teams/${saved.team.slug}/me/permissions`)
      expect(permissions.data.roleName).toBe(role === 'OTHER_TEAM_ADMIN' ? null : role)
      await session.api.storageState({ path: `tests/e2e/.auth/team-payment-${role}.json` })
    }
    team = saved.team
    inviteId = saved.inviteId
    setupComplete = true
    return
  }
  const createTeam: components['schemas']['CreateTeamRequest'] = { name: `BillingAccess_${Date.now()}`, visibility: 'PUBLIC' }
  const created = (await json<TeamResponse>(admin.api, '/teams', 'POST', createTeam)).data
  expect(typeof created.slug, '作成応答のslug').toBe('string')
  const myTeams = (await json<Array<{ id: number; slug: string }>>(admin.api, '/me/teams')).data
  expect(Array.isArray(myTeams), '数値チームID解決用の所属一覧').toBe(true)
  const numericId: unknown = myTeams.find((candidate: { slug: string }) => candidate.slug === created.slug)?.id
  if (typeof numericId !== 'number' || !Number.isSafeInteger(numericId) || numericId <= 0) {
    throw new Error('created team numeric ID was not returned by /me/teams')
  }
  team = { id: numericId, slug: created.slug }
  const outsiderTeams = (await json<Array<{ id: number; role: string }>>(sessions.get('OTHER_TEAM_ADMIN')!.api, '/me/teams')).data
  expect(outsiderTeams.some((scope) => scope.role === 'ADMIN' && scope.id !== numericId), '別チームの実ADMINであること').toBe(true)
  expect(outsiderTeams.some((scope) => scope.id === numericId), '対象チームには未所属であること').toBe(false)
  const invitation = (await json<{ id: number; token: string }>(admin.api, `/teams/${team!.slug}/invite-tokens`, 'POST', {
    roleId: 4, expiresIn: '1d', maxUses: 2,
  })).data
  inviteId = invitation.id
  for (const role of ['DEPUTY_ADMIN', 'MEMBER']) {
    await send(sessions.get(role)!.api, `/invite/${invitation.token}/join`, 'POST')
    joinedRoles.push(role)
  }
  const deputyId = sessions.get('DEPUTY_ADMIN')!.user.id
  const changed = await admin.api.patch(`${BE}/api/v1/teams/${team!.slug}/members/${deputyId}/role`, { data: { roleId: 3 } })
  expect(changed.status()).toBe(200)
  const currentTeam = (await json<TeamResponse>(admin.api, `/teams/${team!.slug}`)).data
  const version = currentTeam.metadata?.version
  if (typeof version !== 'number') throw new Error('team metadata.version is required')
  const updateTeam: components['schemas']['UpdateTeamRequest'] = { supporterEnabled: true, version }
  await send(admin.api, `/teams/${team!.slug}`, 'PATCH', updateTeam)
  await send(sessions.get('SUPPORTER')!.api, `/teams/${team!.slug}/follow`, 'POST')
  joinedRoles.push('SUPPORTER')
  for (const role of ['ADMIN', 'DEPUTY_ADMIN', 'MEMBER', 'SUPPORTER']) {
    const permissions = await json<{ roleName: string }>(sessions.get(role)!.api, `/teams/${team!.slug}/me/permissions`)
    expect(permissions.data.roleName, '実ロールの前提確認').toBe(role)
  }
  setupComplete = true
  if (process.env.KEEP_PAYMENT_ACCESS_FIXTURE === 'true') {
    for (const [role, session] of sessions) {
      await session.api.storageState({ path: `tests/e2e/.auth/team-payment-${role}.json` })
    }
    await writeFile('tests/e2e/.auth/team-payment-fixture.json', JSON.stringify({
      team, inviteId, users: Object.fromEntries([...sessions].map(([role, session]) => [role, session.user])),
    }))
  }
})

test.afterAll(async () => {
  const admin = sessions.get('ADMIN')
  const keepFixture = process.env.KEEP_PAYMENT_ACCESS_FIXTURE === 'true'
  // 承認待ちの検証ではsetup途中の失敗でも削除せず、作成scopeをrun別に保全する。
  if (team && keepFixture) {
    await writeFile(`tests/e2e/.auth/team-payment-fixture-${team.id}.json`, JSON.stringify({
      team, inviteId, setupComplete, joinedRoles,
      users: Object.fromEntries([...sessions].map(([role, session]) => [role, session.user])),
    }))
  }
  if (team && admin && !keepFixture) {
    if (inviteId) {
      const revoked = await admin.api.delete(`${BE}/api/v1/teams/${team.slug}/invite-tokens/${inviteId}`)
      expect(revoked.ok()).toBe(true)
    }
    for (const role of joinedRoles) {
      const session = sessions.get(role)
      if (session) {
        const removed = role === 'SUPPORTER'
          ? await session.api.delete(`${BE}/api/v1/teams/${team.slug}/follow`)
          : await admin.api.delete(`${BE}/api/v1/teams/${team.slug}/members/${session.user.id}`)
        expect(removed.ok(), `${role}の専用チーム所属を後始末`).toBe(true)
      }
    }
    const deleted = await admin.api.delete(`${BE}/api/v1/teams/${team.slug}`)
    expect(deleted.status(), '専用チームを後始末').toBe(204)
  }
  await Promise.all([...sessions.values()].map((session) => session.api.dispose()))
})

for (const role of ['ADMIN', 'DEPUTY_ADMIN']) {
  test(`${role}: 管理ハブ→請求・再読込・立替・詳細URLへ到達する`, async ({ page }) => {
    test.setTimeout(180_000)
    const base = `/teams/${team!.slug}`
    await open(page, role, `${base}/admin`)
    const requestList = page.waitForResponse((response) => response.url().includes(`/teams/${team!.id}/payment-requests`) && response.request().method() === 'GET')
    await page.locator(`a[href="${base}/payment-requests"]`).click()
    expect((await requestList).status()).toBe(200)
    await expect(page.getByText('請求はありません', { exact: true })).toBeVisible()
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByText('請求はありません', { exact: true })).toBeVisible()
    await page.getByTestId('payment-advances-link').click()
    await expect(page).toHaveURL(new RegExp(`${base}/payments/advances$`))
    await expect(page.getByText('請求はありません', { exact: true })).toBeVisible()
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByText('請求はありません', { exact: true })).toBeVisible()
    const detail = page.waitForResponse((response) => response.url().includes(`/teams/${team!.id}/payment-requests/${missingRequestId}`))
    await page.goto(`${base}/payment-requests/${missingRequestId}`, { waitUntil: 'domcontentloaded' })
    expect((await detail).status()).toBe(404)
    await expect(page.getByTestId('load-error-state')).toBeVisible()
    await expect(page.getByText('このページは管理者のみアクセスできます', { exact: true })).toHaveCount(0)
  })
}

for (const role of ['MEMBER', 'SUPPORTER', 'OTHER_TEAM_ADMIN']) {
  test(`${role}: 請求一覧/詳細/立替を直URLでも閲覧できない`, async ({ page }) => {
    test.setTimeout(180_000)
    const base = `/teams/${team!.slug}`
    for (const suffix of ['/payment-requests', `/payment-requests/${missingRequestId}`, '/payments/advances']) {
      await open(page, role, `${base}${suffix}`)
      await expect(page.getByText('このページは管理者のみアクセスできます', { exact: true })).toBeVisible()
      await expect(page.getByTestId('payment-advances-link')).toHaveCount(0)
      await expect(page.getByTestId('payment-request-pay')).toHaveCount(0)
      await expect(page.getByTestId('payment-request-status')).toHaveCount(0)
      await expect(page.getByTestId('payment-advance-status')).toHaveCount(0)
    }
  })
}
