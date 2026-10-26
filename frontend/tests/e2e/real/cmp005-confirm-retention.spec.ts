/**
 * CMP-005 Wave4 AC-14 — Undo を付けない破壊操作の代表標本。
 *
 * 実サービス・実 DB のみを使い、使い捨てチーム／プロジェクトだけを変更する。
 * AWS や外部サービスには接続しない。
 */
import {
  expect,
  request,
  test,
  type APIRequestContext,
  type APIResponse,
  type Dialog,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
// e2e-admin は SYSTEM_ADMIN（テナント変更は監査 read-only）なので、
// 使い捨てチームを自ら作成して ADMIN になる一般利用者を操作主体にする。
const ADMIN = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const MEMBER = { email: 'e2e-supporter@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD }

interface LoginResult {
  accessToken: string
  userId: number
}

interface TeamFixture {
  id: number
  slug: string
}

interface ProjectFixture {
  id: number
  title: string
}

interface MemberRow {
  userId: number
  displayName: string
}

let adminApi: APIRequestContext
let memberApi: APIRequestContext
let outsiderApi: APIRequestContext
let unauthenticatedApi: APIRequestContext
let adminLogin: LoginResult
let memberLogin: LoginResult
let outsiderLogin: LoginResult
let targetTeam: TeamFixture
let outsiderTeam: TeamFixture
let targetProject: ProjectFixture
let outsiderProject: ProjectFixture
let memberDisplayName = ''

async function login(api: APIRequestContext, credentials: typeof ADMIN): Promise<LoginResult> {
  const response = await api.post('/api/v1/auth/login', { data: credentials })
  expect(response.status(), `${credentials.email} のログイン`).toBe(200)
  return ((await response.json()) as { data: LoginResult }).data
}

function authorization(loginResult: LoginResult): Record<string, string> {
  return { Authorization: `Bearer ${loginResult.accessToken}` }
}

async function createTeam(
  api: APIRequestContext,
  loginResult: LoginResult,
  prefix: string,
): Promise<TeamFixture> {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const response = await api.post('/api/v1/teams', {
    headers: authorization(loginResult),
    data: {
      name: `${prefix}-${suffix}`,
      slug: `${prefix}-${suffix}`.toLowerCase().slice(0, 30),
      visibility: 'MEMBERS_AND_ABOVE',
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  const body = (await response.json()) as { data: { numericId: number; slug: string } }
  return { id: body.data.numericId, slug: body.data.slug }
}

async function createProject(
  api: APIRequestContext,
  loginResult: LoginResult,
  team: TeamFixture,
  prefix: string,
): Promise<ProjectFixture> {
  const title = `${prefix}-${Date.now()}`
  const response = await api.post(`/api/v1/teams/${team.slug}/projects`, {
    headers: authorization(loginResult),
    data: { title, description: 'CMP-005 Wave4 disposable fixture' },
  })
  expect(response.status(), await response.text()).toBe(201)
  const body = (await response.json()) as { data: { id: number; title: string } }
  return { id: body.data.id, title: body.data.title }
}

async function members(
  api: APIRequestContext,
  team: TeamFixture,
  loginResult?: LoginResult,
): Promise<MemberRow[]> {
  const response = await api.get(
    `${API_BASE}/api/v1/teams/${team.slug}/members?page=0&size=100`,
    loginResult ? { headers: authorization(loginResult) } : undefined,
  )
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()) as { data: MemberRow[] }).data
}

async function expectErrorCode(
  response: APIResponse,
  status: number,
  code: string,
  label: string,
): Promise<void> {
  expect(response.status(), label).toBe(status)
  const body = (await response.json()) as { error: { code: string } }
  expect(body.error.code, `${label} のエラーコード`).toBe(code)
}

async function loginPage(page: Page): Promise<void> {
  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE })
}

function countDeletes(page: Page, path: string): { value: () => number } {
  let count = 0
  page.on('request', (req) => {
    if (req.method() === 'DELETE' && new URL(req.url()).pathname === path) count += 1
  })
  return { value: () => count }
}

test.beforeAll(async () => {
  adminApi = await request.newContext({ baseURL: API_BASE })
  memberApi = await request.newContext({ baseURL: API_BASE })
  outsiderApi = await request.newContext({ baseURL: API_BASE })
  unauthenticatedApi = await request.newContext({
    baseURL: API_BASE,
    storageState: { cookies: [], origins: [] },
  })

  adminLogin = await login(adminApi, ADMIN)
  memberLogin = await login(memberApi, MEMBER)
  outsiderLogin = await login(outsiderApi, OUTSIDER)

  targetTeam = await createTeam(adminApi, adminLogin, 'cmp005-confirm')
  outsiderTeam = await createTeam(outsiderApi, outsiderLogin, 'cmp005-other')
  targetProject = await createProject(adminApi, adminLogin, targetTeam, 'CMP005削除確認')
  outsiderProject = await createProject(outsiderApi, outsiderLogin, outsiderTeam, 'CMP005別スコープ')

  const invite = await adminApi.post(`/api/v1/teams/${targetTeam.slug}/invite-tokens`, {
    headers: authorization(adminLogin),
    data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
  })
  expect(invite.status(), await invite.text()).toBe(201)
  const token = ((await invite.json()) as { data: { token: string } }).data.token
  const join = await memberApi.post(`/api/v1/invite/${token}/join`, {
    headers: authorization(memberLogin),
  })
  expect(join.status(), await join.text()).toBe(200)

  const member = (await members(adminApi, targetTeam, adminLogin))
    .find(row => row.userId === memberLogin.userId)
  expect(member, '使い捨てチームに除名対象 MEMBER が存在').toBeTruthy()
  memberDisplayName = member!.displayName
})

test.afterAll(async () => {
  const cleanupErrors: unknown[] = []
  try {
    if (targetTeam?.slug) {
      try {
        adminLogin = await login(adminApi, ADMIN)
        const response = await adminApi.delete(`/api/v1/teams/${targetTeam.slug}`, {
          headers: authorization(adminLogin),
        })
        expect(response.status(), `使い捨てチーム ${targetTeam.slug} の後始末`).toBe(204)
      }
      catch (error) {
        cleanupErrors.push(error)
      }
    }
    if (outsiderTeam?.slug) {
      try {
        const response = await outsiderApi.delete(`/api/v1/teams/${outsiderTeam.slug}`, {
          headers: authorization(outsiderLogin),
        })
        expect(response.status(), `使い捨てチーム ${outsiderTeam.slug} の後始末`).toBe(204)
      }
      catch (error) {
        cleanupErrors.push(error)
      }
    }
  }
  finally {
    await Promise.all([
      adminApi?.dispose(),
      memberApi?.dispose(),
      outsiderApi?.dispose(),
      unauthenticatedApi?.dispose(),
    ])
  }
  if (cleanupErrors.length > 0) {
    throw new AggregateError(cleanupErrors, 'CMP-005 Wave4 のテストデータ後始末に失敗しました')
  }
})

test('CONFIRM-REAL-001: プロジェクト削除は取消で不変、連打でもDELETE一回、認可境界を守る', async ({ page }) => {
  const path = `/api/v1/teams/${targetTeam.slug}/projects/${targetProject.id}`

  expect((await unauthenticatedApi.delete(path)).status(), '未認証削除').toBe(401)
  await expectErrorCode(
    await outsiderApi.delete(path, { headers: authorization(outsiderLogin) }),
    403,
    'COMMON_002',
    '別チーム管理者による非所属削除',
  )
  await expectErrorCode(
    await adminApi.delete(`/api/v1/teams/${targetTeam.slug}/projects/${outsiderProject.id}`, {
      headers: authorization(adminLogin),
    }),
    404,
    'TODO_001',
    '別scopeのprojectId削除',
  )
  expect((await adminApi.get(path, { headers: authorization(adminLogin) })).status(), '認可拒否後もプロジェクトは存在').toBe(200)

  await loginPage(page)
  await page.goto(`/teams/${targetTeam.slug}/projects`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const button = page.getByTestId(`team-project-delete-${targetProject.id}`)
  await expect(button).toBeVisible({ timeout: 30_000 })
  const deletes = countDeletes(page, path)

  page.once('dialog', dialog => dialog.dismiss())
  await button.click()
  expect(deletes.value(), '取消時はDELETEを送らない').toBe(0)
  expect((await page.request.get(`${API_BASE}${path}`)).status(), '取消後もプロジェクトは存在').toBe(200)
  await page.reload()
  await expect(page.getByText(targetProject.title).first(), '再読込後もプロジェクトを表示').toBeVisible()

  let acceptedDeleteDialogs = 0
  const acceptDeleteDialogs = async (dialog: Dialog) => {
    acceptedDeleteDialogs += 1
    await dialog.accept()
  }
  page.on('dialog', acceptDeleteDialogs)
  const deleteResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && new URL(response.url()).pathname === path,
  )
  await page.getByTestId(`team-project-delete-${targetProject.id}`).evaluate((element) => {
    const buttonElement = element as HTMLButtonElement
    buttonElement.click()
    buttonElement.click()
  })
  page.off('dialog', acceptDeleteDialogs)
  expect(acceptedDeleteDialogs, '連打してもプロジェクト削除確認は一回').toBe(1)
  expect((await deleteResponse).status(), 'UIからのプロジェクト削除').toBe(204)
  await expect(page.getByTestId(`team-project-delete-${targetProject.id}`)).toHaveCount(0)
  expect(deletes.value(), '連打してもプロジェクトDELETEは一回').toBe(1)
  expect((await page.request.get(`${API_BASE}${path}`)).status(), '削除後は論理削除され取得不可').toBe(404)
})

test('CONFIRM-REAL-002: メンバー除名は取消で不変、連打でもDELETE一回、二重認可で他scopeを拒否する', async ({ page }) => {
  const path = `/api/v1/teams/${targetTeam.slug}/members/${memberLogin.userId}`

  expect((await unauthenticatedApi.delete(path)).status(), '未認証除名').toBe(401)
  await expectErrorCode(
    await outsiderApi.delete(path, { headers: authorization(outsiderLogin) }),
    403,
    'COMMON_002',
    '別チーム管理者による除名',
  )
  expect((await members(memberApi, targetTeam, memberLogin)).some(row => row.userId === memberLogin.userId)).toBe(true)

  await loginPage(page)
  await page.goto(`/teams/${targetTeam.slug}/members`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const button = page.getByTestId(`member-remove-${memberLogin.userId}`)
  await expect(button).toBeVisible({ timeout: 30_000 })
  const deletes = countDeletes(page, path)

  page.once('dialog', dialog => dialog.dismiss())
  await button.click()
  expect(deletes.value(), '取消時はDELETEを送らない').toBe(0)
  expect((await members(page.request, targetTeam)).some(row => row.userId === memberLogin.userId)).toBe(true)
  await page.reload()
  await expect(page.getByText(memberDisplayName).first(), '再読込後もメンバーを表示').toBeVisible()

  let acceptedRemoveDialogs = 0
  const acceptRemoveDialogs = async (dialog: Dialog) => {
    acceptedRemoveDialogs += 1
    await dialog.accept()
  }
  page.on('dialog', acceptRemoveDialogs)
  const deleteResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && new URL(response.url()).pathname === path,
  )
  await page.getByTestId(`member-remove-${memberLogin.userId}`).evaluate((element) => {
    const buttonElement = element as HTMLButtonElement
    buttonElement.click()
    buttonElement.click()
  })
  page.off('dialog', acceptRemoveDialogs)
  expect(acceptedRemoveDialogs, '連打してもメンバー除名確認は一回').toBe(1)
  expect((await deleteResponse).status(), 'UIからのメンバー除名').toBe(204)
  await expect(page.getByTestId(`member-remove-${memberLogin.userId}`)).toHaveCount(0)
  expect(deletes.value(), '連打してもメンバーDELETEは一回').toBe(1)
  expect((await members(page.request, targetTeam)).some(row => row.userId === memberLogin.userId)).toBe(false)
})

test('CONFIRM-REAL-003: アカウント削除は二段確認を取消でき、パスワード未入力ではDELETEしない', async ({ page }) => {
  await loginPage(page)
  const path = '/api/v1/users/me'
  const deletes = countDeletes(page, path)
  await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)

  const start = page.getByRole('button', { name: 'アカウントを削除', exact: true })
  await expect(start).toBeVisible({ timeout: 60_000 })
  await start.click()
  const firstBarrier = page.locator('.p-confirmdialog')
  await expect(firstBarrier).toBeVisible()
  await firstBarrier.getByRole('button', { name: 'キャンセル', exact: true }).click()
  expect(deletes.value(), '第一確認の取消ではDELETEを送らない').toBe(0)

  await start.click()
  await expect(firstBarrier).toBeVisible()
  const previewResponse = page.waitForResponse(response =>
    response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/v1/account/deletion-preview',
  )
  await firstBarrier.getByRole('button', { name: '削除の手続きへ進む', exact: true }).click()
  expect((await previewResponse).status(), '削除プレビュー取得').toBe(200)

  const secondBarrier = page.getByTestId('settings-deletion-preview-dialog')
  await expect(secondBarrier).toBeVisible()
  await expect(secondBarrier.locator('#deletePassword')).toBeVisible()
  await expect(page.getByTestId('settings-deletion-preview-delete-button')).toBeDisabled()
  expect(deletes.value(), 'パスワード未入力ではDELETEを送らない').toBe(0)
  await secondBarrier.getByRole('button', { name: 'キャンセル', exact: true }).click()
  await expect(secondBarrier).toBeHidden()
  expect(deletes.value(), '第二確認の取消でもDELETEを送らない').toBe(0)
  expect((await page.request.get(`${API_BASE}/api/v1/users/me`)).status(), '取消後もアカウントは有効').toBe(200)
})
