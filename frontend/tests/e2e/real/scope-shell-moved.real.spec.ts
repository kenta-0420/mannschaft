/** CMP-261006-0632 M5。競合順序は実ページdeferred unit、ここでは実APIと画面の正常遷移・認可を検証する。 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const admin = { email: 'e2e-admin@test.mannschaft.local', password }
const member = { email: 'e2e-user@test.mannschaft.local', password }
const outsider = { email: 'e2e-outsider@test.mannschaft.local', password }
type Scope = 'teams' | 'organizations'
interface Owned {
  scope: Scope
  numericId: number
  slug: string
  oldSlug: string
  name: string
  owner: typeof admin
}
const fixtures: Owned[] = []
async function tokenFor(request: APIRequestContext, who: typeof admin) {
  const response = await request.post(`${API}/api/v1/auth/login`, { data: who })
  expect(response.status()).toBe(200)
  return (await response.json()).data.accessToken as string
}
async function create(
  request: APIRequestContext,
  scope: Scope,
  owner: typeof admin,
  suffix: string,
) {
  const token = await tokenFor(request, owner)
  const slug = `e2e-0632-${scope === 'teams' ? 't' : 'o'}-${suffix}`.slice(0, 30)
  const name = `MOVED0632-${scope}-${suffix}`
  const response = await request.post(`${API}/api/v1/${scope}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name,
      slug,
      visibility: scope === 'teams' ? 'MEMBERS_AND_ABOVE' : 'PRIVATE',
      ...(scope === 'organizations' ? { orgType: 'OTHER' } : {}),
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  const data = (await response.json()).data as {
    numericId: number
    slug: string
    basicInfo: { name: string }
  }
  const fixture = {
    scope,
    numericId: data.numericId,
    slug: data.slug,
    oldSlug: data.slug,
    name,
    owner,
  }
  fixtures.push(fixture)
  expect(data.basicInfo.name).toBe(name)
  expect(data.numericId).toBeGreaterThan(0)
  return fixture
}
test.beforeAll(async ({ request }, info) => {
  test.setTimeout(120_000)
  const suffix = Date.now().toString(36)
  for (const scope of ['organizations', 'teams'] as const) {
    const fixture = await create(request, scope, admin, suffix)
    const adminToken = await tokenFor(request, admin)
    const memberToken = await tokenFor(request, member)
    const invite = await request.post(`${API}/api/v1/${scope}/${fixture.slug}/invite-tokens`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
    })
    expect(invite.status(), await invite.text()).toBe(201)
    const join = await request.post(
      `${API}/api/v1/invite/${(await invite.json()).data.token}/join`,
      { headers: { Authorization: `Bearer ${memberToken}` } },
    )
    expect(join.ok(), await join.text()).toBe(true)
  }
  await create(request, 'teams', outsider, `x${suffix}`)
  await info.attach('所有fixture', {
    body: JSON.stringify(
      fixtures.map(({ owner: _owner, ...f }) => f),
      null,
      2,
    ),
    contentType: 'application/json',
  })
})
test.afterAll(async ({ request }, info) => {
  test.setTimeout(120_000)
  await info.attach('最終所有fixture', {
    body: JSON.stringify(
      fixtures.map(({ owner: _owner, ...f }) => f),
      null,
      2,
    ),
    contentType: 'application/json',
  })
  const failures: string[] = []
  const cleanupResults: Array<{
    scope: Scope
    numericId: number
    ownerVerified: boolean
    deleted: number
    absent: number
  }> = []
  for (const f of [...fixtures].reverse()) {
    try {
      const headers = { Authorization: `Bearer ${await tokenFor(request, f.owner)}` }
      // entity詳細はslug契約。リネーム成功後に画面検証が失敗した場合も履歴から回収する。
      let response = await request.get(`${API}/api/v1/${f.scope}/${f.slug}`, { headers })
      if (response.status() === 404) {
        const resolved = await request.get(`${API}/api/v1/public/${f.scope}/slug-resolve`, {
          params: { slug: f.oldSlug },
        })
        expect(resolved.status()).toBe(200)
        const resolution = (await resolved.json()).data as {
          status: string
          canonicalSlug?: string
        }
        expect(resolution.status).toBe('MOVED')
        expect(resolution.canonicalSlug).toBeTruthy()
        response = await request.get(`${API}/api/v1/${f.scope}/${resolution.canonicalSlug}`, {
          headers,
        })
      }
      expect(response.status()).toBe(200)
      const actual = (await response.json()).data as {
        numericId: number
        slug: string
        basicInfo: { name: string }
      }
      expect(actual.numericId).toBe(f.numericId)
      expect(actual.basicInfo.name).toBe(f.name)
      const path = `${API}/api/v1/${f.scope}/${actual.slug}`
      const deleted = (await request.delete(path, { headers })).status()
      expect(deleted).toBe(204)
      const absent = (await request.get(path, { headers })).status()
      expect(absent).toBe(404)
      cleanupResults.push({
        scope: f.scope,
        numericId: f.numericId,
        ownerVerified: true,
        deleted,
        absent,
      })
    } catch (error) {
      failures.push(`${f.scope}/${f.numericId}: ${String(error)}`)
    }
  }
  await info.attach('所有照合と削除結果', {
    body: JSON.stringify(cleanupResults),
    contentType: 'application/json',
  })
  expect(failures, '所有fixture後始末').toEqual([])
})
async function open(page: Page, path: string) {
  const response = await page.goto(path)
  await test.info().attach('document-status', {
    body: JSON.stringify({ path, status: response?.status() }),
    contentType: 'application/json',
  })
  await waitForHydration(page)
}
test.afterEach(async ({ page }, info) => {
  await info.attach('最終URL', { body: page.url(), contentType: 'text/plain' })
})
for (const scope of ['organizations', 'teams'] as const) {
  test(`M5 ${scope}: 管理者が画面でslug変更し旧URLのsubpath/query/hashと往復を維持`, async ({
    page,
  }) => {
    test.setTimeout(180_000)
    const fixture = fixtures.find((f) => f.scope === scope && f.owner === admin)!
    await loginViaApi(page, admin)
    await open(page, `/${scope}/${fixture.slug}/settings/public-settings`)
    await expect(page.getByTestId('slug-rename-field')).toBeVisible()
    const next = `${fixture.slug.slice(0, 28)}-n`
    await page.getByTestId('slug-rename-input').fill(next)
    await expect(page.getByTestId('slug-rename-save')).toBeEnabled()
    await page.getByTestId('slug-rename-save').click()
    await expect(page).toHaveURL(new RegExp(`/${scope}/${next}/settings/public-settings$`))
    fixture.slug = next
    await expect(page.getByTestId('slug-rename-input')).toHaveValue(next)
    await open(page, `/${scope}/${fixture.oldSlug}/info?view=detail#overview`)
    await expect(page).toHaveURL(new RegExp(`/${scope}/${next}/info\\?view=detail#overview$`))
    await expect(
      page.getByRole('heading', { name: fixture.name, exact: true }).first(),
    ).toBeVisible()
    const other = fixtures.find((f) => f.scope !== scope && f.owner === admin)!
    await open(page, `/${other.scope}/${other.slug}/info`)
    await expect(page.getByRole('heading', { name: other.name, exact: true }).first()).toBeVisible()
    await open(page, `/${scope}/${fixture.oldSlug}/info`)
    await expect(page).toHaveURL(new RegExp(`/${scope}/${next}/info$`))
    await expect(
      page.getByRole('heading', { name: fixture.name, exact: true }).first(),
    ).toBeVisible()
  })
  test(`M5 ${scope}: 非管理者は旧URLから詳細閲覧でき公開設定を編集できない`, async ({ page }) => {
    const f = fixtures.find((f) => f.scope === scope && f.owner === admin)!
    await loginViaApi(page, member)
    await open(page, `/${scope}/${f.oldSlug}/info`)
    await expect(page).toHaveURL(new RegExp(`/${scope}/${f.slug}/info$`))
    await expect(page.getByRole('heading', { name: f.name, exact: true }).first()).toBeVisible()
    await open(page, `/${scope}/${f.slug}/settings/public-settings`)
    await expect(page.getByTestId('slug-rename-field')).toHaveCount(0)
    await expect(
      page.getByTestId(
        scope === 'teams' ? 'team-public-settings-form' : 'org-public-settings-form',
      ),
    ).toHaveCount(0)
    // DashboardErrorStateは403を再試行不可のアクセス拒否として表示する。
    await expect(page.getByText('この画面をご利用いただけません', { exact: true })).toBeVisible()
  })
  test(`M5 ${scope}: 他テナント管理者の旧URL直打ちで非公開詳細を漏らさない`, async ({ page }) => {
    const f = fixtures.find((f) => f.scope === scope && f.owner === admin)!
    await loginViaApi(page, outsider)
    await open(page, `/${scope}/${f.oldSlug}/info`)
    await expect(page).toHaveURL(new RegExp(`/${scope}/${f.slug}/info$`))
    await expect(page.getByRole('heading', { name: f.name, exact: true })).toHaveCount(0)
    await expect(page.getByText('情報を取得できませんでした', { exact: true })).toBeVisible()
  })
}
