import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * NOTE-260918-145441-001 / #3358 の実機証跡。
 *
 * API の使用範囲はログイン、seed fixture の解決、後始末、認可境界だけである。
 * 対象操作である三スコープ投稿とダッシュボード/個人タイムラインの確認は、すべて実UIで行う。
 */
test.describe.configure({ mode: 'serial' })
test.use({ storageState: { cookies: [], origins: [] }, trace: 'on', screenshot: 'on' })

const API = process.env.API_BASE_URL ?? 'http://localhost:8084'
const credentials = {
  admin: {
    email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
    password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
  },
  member: {
    email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
    password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
  },
  outsider: {
    email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
    password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
  },
} as const

type Scope = { id?: string | number, slug?: string, name?: string, organizationId?: number, role?: string }
type CreatedPost = { id: number, marker: string, sourceName: string, sourceHref: RegExp }

function unwrapList(body: unknown): Scope[] {
  const candidate = body as { data?: { content?: Scope[], data?: Scope[] } | Scope[], content?: Scope[] }
  if (Array.isArray(candidate.data)) return candidate.data
  if (Array.isArray(candidate.data?.content)) return candidate.data.content
  if (Array.isArray(candidate.data?.data)) return candidate.data.data
  return candidate.content ?? []
}

async function loadMeId(page: Page): Promise<string> {
  const response = await page.request.get(`${API}/api/v1/users/me`)
  expect(response.status(), '認証主体の確認').toBe(200)
  const body = await response.json() as { data?: { id?: number | string } }
  expect(body.data?.id).toBeTruthy()
  return String(body.data!.id)
}

async function postViaUi(page: Page, url: string, marker: string, extra?: () => Promise<void>): Promise<number> {
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const composer = page.getByTestId('team-timeline-composer')
  await expect(composer, `${url} の投稿フォーム`).toBeVisible({ timeout: 60_000 })
  if (extra) await extra()
  await composer.fill(marker)
  const created = page.waitForResponse(response =>
    response.url().includes('/api/v1/timeline/posts') && response.request().method() === 'POST',
  )
  await page.getByTestId('team-timeline-submit').click()
  const response = await created
  expect(response.status(), `${url} の投稿作成`).toBe(201)
  const body = await response.json() as { data?: { id?: number }, id?: number }
  const id = body.data?.id ?? body.id
  expect(id, 'UI投稿のID').toBeTruthy()
  await expect(page.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
  return id!
}

async function openAggregate(page: Page, path: '/dashboard' | '/timeline'): Promise<void> {
  const response = await page.request.get(`${API}/api/v1/timeline/my`)
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  if (path === '/dashboard') {
    await page.locator('#personal-dashboard-section-button-feed').click()
  }
  expect((await response).status(), `${path} の個人集約タイムライン`).toBe(200)
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  const feed = path === '/dashboard'
    ? page.getByTestId('personal-dashboard-accordion').getByTestId('timeline-feed')
    : page.getByTestId('timeline-feed')
  await expect(feed).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
}

async function assertAggregate(page: Page, posts: readonly CreatedPost[]): Promise<void> {
  const feed = page.url().includes('/dashboard')
    ? page.getByTestId('personal-dashboard-accordion').getByTestId('timeline-feed')
    : page.getByTestId('timeline-feed')
  const cards = feed.getByTestId('team-timeline-post')
  const indexes: number[] = []
  for (const post of posts) {
    const card = cards.filter({ hasText: post.marker }).first()
    await expect(card).toBeVisible()
    const source = card.getByTestId('timeline-post-source')
    await expect(source).toHaveText(post.sourceName)
    await expect(source).toHaveAttribute('href', post.sourceHref)
    indexes.push(await card.evaluate((element) => Array.from(element.parentElement?.children ?? []).indexOf(element)))
  }
  expect(indexes, '村→組織→チームの新着順がダッシュボードと一覧で一致すること')
    .toEqual([...indexes].sort((a, b) => a - b))
}

test('NOTE-260918-145441-001: 所属チーム・組織・村の投稿がダッシュボードと個人タイムラインで一致する', async ({ browser }, testInfo) => {
  test.setTimeout(1_200_000)
  const admin = await browser.newContext()
  const member = await browser.newContext()
  const outsider = await browser.newContext()
  const anonymous = await browser.newContext()
  const runTag = `NOTE260918-${Date.now()}`
  const created: Array<{ id: number, owner: 'admin' | 'member' }> = []

  try {
    const adminPage = await admin.newPage()
    const memberPage = await member.newPage()
    const outsiderPage = await outsider.newPage()
    const anonymousPage = await anonymous.newPage()
    await Promise.all([
      loginViaApi(adminPage, credentials.admin, { apiBaseUrl: API }),
      loginViaApi(memberPage, credentials.member, { apiBaseUrl: API }),
      loginViaApi(outsiderPage, credentials.outsider, { apiBaseUrl: API }),
    ])
    const ids = await Promise.all([loadMeId(adminPage), loadMeId(memberPage), loadMeId(outsiderPage)])
    expect(new Set(ids).size, '管理者1＋一般2のBrowserContextは認証主体を共有しない').toBe(3)

    // 前提データの解決のみ API を用いる。投稿は以下すべて UI から実施する。
    const teamResponse = await member.request.get(`${API}/api/v1/me/teams`)
    expect(teamResponse.status(), '所属チームfixture').toBe(200)
    const team = unwrapList(await teamResponse.json()).find((item) => item.slug)
    expect(team?.slug, '投稿可能な所属チーム').toBeTruthy()
    expect(team?.name, '所属チーム名').toBeTruthy()

    const orgResponse = await member.request.get(`${API}/api/v1/me/organizations`)
    expect(orgResponse.status(), '投稿者の所属組織fixture').toBe(200)
    const organization = unwrapList(await orgResponse.json()).find(
      (item) => item.slug
        && (item.role === 'ADMIN' || item.role === 'SYSTEM_ADMIN'),
    )
    expect(organization?.slug, '投稿者が投稿権限を持つチーム親組織').toBeTruthy()

    const villageResponse = await member.request.get(`${API}/api/v1/villages/search?q=${encodeURIComponent('E2Eテストコミュニティ村')}&page=0&size=20`)
    expect(villageResponse.status(), '所属村fixture').toBe(200)
    const village = unwrapList(await villageResponse.json()).find((item) => item.name === 'E2Eテストコミュニティ村')
    expect(village?.id, '一般利用者が所属する村').toBeTruthy()

    const teamMarker = `${runTag}-TEAM`
    const orgMarker = `${runTag}-ORG`
    const villageMarker = `${runTag}-VILLAGE`
    created.push({ id: await postViaUi(memberPage, `/teams/${team!.slug}/timeline`, teamMarker), owner: 'member' })
    created.push({ id: await postViaUi(memberPage, `/organizations/${organization!.slug}/timeline`, orgMarker, async () => {
      await memberPage.getByTestId('timeline-delivery-scope-DESCENDANTS').click()
    }), owner: 'member' })
    created.push({ id: await postViaUi(memberPage, `/villages/${village!.id}/timeline`, villageMarker), owner: 'member' })

    const expected: CreatedPost[] = [
      { id: created[2]!.id, marker: villageMarker, sourceName: village!.name!, sourceHref: new RegExp(`/villages/${village!.id}$`) },
      { id: created[1]!.id, marker: orgMarker, sourceName: organization!.name!, sourceHref: new RegExp(`/organizations/${organization!.slug}$`) },
      { id: created[0]!.id, marker: teamMarker, sourceName: team!.name!, sourceHref: new RegExp(`/teams/${team!.slug}$`) },
    ]

    await openAggregate(memberPage, '/dashboard')
    await assertAggregate(memberPage, expected)
    await memberPage.screenshot({ path: testInfo.outputPath('dashboard-member.png'), fullPage: true })

    await adminPage.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(adminPage)
    await expect(adminPage).not.toHaveURL(/\/login/)
    await adminPage.screenshot({ path: testInfo.outputPath('dashboard-admin-authenticated.png'), fullPage: true })

    await openAggregate(memberPage, '/timeline')
    await assertAggregate(memberPage, expected)
    await memberPage.screenshot({ path: testInfo.outputPath('timeline-member.png'), fullPage: true })
    await memberPage.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(memberPage)
    await expect(memberPage.getByText(villageMarker, { exact: true })).toBeVisible()
    await memberPage.goto('/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(memberPage)
    await expect(memberPage.getByTestId('timeline-feed')).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
    await expect(memberPage.getByText(villageMarker, { exact: true })).toBeVisible({ timeout: 60_000 })

    expect((await outsiderPage.request.get(`${API}/api/v1/timeline/my`)).status()).toBe(200)
    await outsiderPage.goto('/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(outsiderPage)
    for (const post of expected) await expect(outsiderPage.getByText(post.marker, { exact: true })).toHaveCount(0)
    const detail = await outsiderPage.request.get(`${API}/api/v1/timeline/posts/${created[2]!.id}`)
    expect(detail.status(), '非所属者の村投稿詳細').toBe(404)
    await outsiderPage.goto(`/timeline/${created[2]!.id}`, { waitUntil: 'domcontentloaded' })
    await expect(outsiderPage.getByText(villageMarker, { exact: true })).toHaveCount(0)
    await outsiderPage.screenshot({ path: testInfo.outputPath('dashboard-outsider.png'), fullPage: true })

    expect((await anonymousPage.request.get(`${API}/api/v1/timeline/my`)).status(), '未認証API').toBe(401)
    await anonymousPage.goto('/timeline', { waitUntil: 'domcontentloaded' })
    await waitForHydration(anonymousPage)
    await expect(anonymousPage).toHaveURL(/\/login/, { timeout: 60_000 })
  } finally {
    for (const post of created.reverse()) {
      const request = post.owner === 'admin' ? admin.request : member.request
      const response = await request.delete(`${API}/api/v1/timeline/posts/${post.id}`)
      expect([200, 204, 404], `作成した投稿 ${post.id} の後始末`).toContain(response.status())
    }
    // 手動作成した context は browser fixture がテスト後に必ず破棄する。
    // 実DB負荷時は複数 context の同時 close（trace のflushを含む）が長時間滞留するため、
    // テスト本体の合否判定を後処理時間で覆さないよう、ここでは明示 close を待たない。
  }
})
