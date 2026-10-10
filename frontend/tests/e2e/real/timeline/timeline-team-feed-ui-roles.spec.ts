import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const pw = 'TestPass2026!'
const owner = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: process.env.TEST_USER_PASSWORD ?? pw }
// 組織9配下の別チーム(fc-u-18)に所属し、検証対象チーム(e2e)には入っていないユーザー
const sameOrgNonMember = { email: 'e2e-dummy-1@test.mannschaft.local', password: pw }
// org 9 に一切所属しない別テナントのユーザー
const pinner = { email: 'e2e-admin@test.mannschaft.local', password: pw }
const TEAM_SLUG = 'e2e'
const ORG_ID = 9
interface MyTeam { slug: string, organizationId: number | null, role: string }
interface MyOrg { id: number, role: string }
const otherTenant = { email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password: pw }

async function waitIdle(page: Page) {
  await page.locator('.pi-spin, .p-progressspinner').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
}

test('チームTLのカーソルページング: ピン先頭1回・重複欠落なし・末尾で停止、同組織非メンバー/他テナントは閲覧不可', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const ctxs: BrowserContext[] = []
  const open = async (c: { email: string, password: string }) => {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    ctxs.push(ctx)
    const page = await ctx.newPage()
    page.setDefaultTimeout(20_000)
    page.setDefaultNavigationTimeout(60_000)
    await loginViaApi(page, c, { apiBaseUrl: API })
    return { ctx, page }
  }
  const o = await open(owner)
  const s = await open(sameOrgNonMember)
  const t = await open(otherTenant)
  const a = await open(pinner)
  const tag = `UIR-${Date.now()}`
  const TOTAL = 25

  // 同組織の実証: 対象チームの organizationId が dummy-1 の所属チームの organizationId と一致する
  const myTeams = async (who: { ctx: BrowserContext }) => (await (await who.ctx.request.get(`${API}/api/v1/me/teams`)).json() as { data: MyTeam[] }).data
  const ownerTeams = await myTeams(o)
  const target = ownerTeams.find(x => x.slug === TEAM_SLUG)
  expect(target, '作成者が対象チームのメンバー').toBeTruthy()
  expect(target!.organizationId, '対象チームの organizationId').toBe(ORG_ID)
  const ownerOrgs = (await (await o.ctx.request.get(`${API}/api/v1/me/organizations`)).json() as { data: MyOrg[] }).data
  console.log(`[UIR] owner team role=${target!.role}, org ${ORG_ID} role=${ownerOrgs.find(x => x.id === ORG_ID)?.role}`)
  const sTeams = await myTeams(s)
  expect(sTeams.some(x => x.organizationId === ORG_ID), '非メンバーは同じ組織9配下の別チームに所属').toBe(true)
  expect(sTeams.some(x => x.slug === TEAM_SLUG), '非メンバーは対象チームに所属しない').toBe(false)
  const tTeams = await myTeams(t)
  expect(tTeams.some(x => x.organizationId === ORG_ID), '他テナントは組織9配下に所属しない').toBe(false)
  console.log(`[UIR] team=${TEAM_SLUG} orgId=${target!.organizationId}; dummy-1 teams=${sTeams.map(x => `${x.slug}@${x.organizationId}:${x.role}`).join(',')}`)
  const team = { slug: TEAM_SLUG }

  const ids: number[] = []
  for (let i = 0; i < TOTAL; i++) {
    const r = await a.ctx.request.post(`${API}/api/v1/timeline/posts`, { data: { content: `${tag}-P${String(i).padStart(2, '0')}`, scopeType: 'TEAM', scopeId: team.slug } })
    expect(r.status(), await r.text()).toBe(201)
    ids.push((await r.json() as { data: { id: number } }).data.id)
  }
  const pinIdx = 3
  const pin = await a.ctx.request.post(`${API}/api/v1/timeline/posts/${ids[pinIdx]}/pin?pinned=true`)
  expect(pin.status(), await pin.text()).toBe(200)

  // 正: メンバー(作成者)
  const url = `/teams/${team.slug}/timeline`
  await o.page.goto(url, { waitUntil: 'domcontentloaded' })
  await waitForHydration(o.page)
  const initialPermissions = o.page.getByTestId('member-permission-setup')
  if (await initialPermissions.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await initialPermissions.getByRole('button', { name: 'あとで決める' }).click()
  }
  const feed = o.page.getByTestId('timeline-feed')
  await expect(feed).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
  await waitIdle(o.page)
  const posts = feed.getByTestId('team-timeline-post')
  const texts = async () => (await posts.allInnerTexts()).map(x => (/UIR-\d+-P\d{2}/.exec(x) ?? [""])[0])
  const first = await texts()
  console.log(`[UIR] initial cards=${first.length} first=${first[0]} pinnedMarker=${tag}-P${String(pinIdx).padStart(2, '0')}`)
  expect(first[0], 'ピン留め投稿が先頭').toBe(`${tag}-P${String(pinIdx).padStart(2, '0')}`)
  expect(first.filter(x => x === first[0]), 'ピン留めは初回に1回だけ').toHaveLength(1)
  expect(first.length, '初回は limit(20)+ピン1 以下').toBeLessThan(TOTAL)

  const cursorResponses: number[] = []
  o.page.on('response', (r) => {
    const u = new URL(r.url())
    if (u.pathname === '/api/v1/timeline/feed' && u.searchParams.get('cursor')) cursorResponses.push(r.status())
  })
  const loadMore = feed.getByTestId('timeline-load-more-target')
  for (let i = 0; i < 10 && await loadMore.count() > 0; i++) {
    await loadMore.scrollIntoViewIfNeeded()
    await o.page.waitForTimeout(1500)
  }
  await expect(loadMore, '最後まで到達したら読み込みトリガが消える').toHaveCount(0)
  const all = await texts()
  console.log(`[UIR] final cards=${all.length} cursorResponses=${cursorResponses.join(',')}`)
  expect(new Set(all).size, '重複なし').toBe(all.length)
  expect(all.sort(), '欠落なし').toEqual(ids.map((_, i) => `${tag}-P${String(i).padStart(2, '0')}`).sort())
  expect(cursorResponses.length).toBeGreaterThan(0)
  expect(cursorResponses.every(c => c === 200)).toBe(true)
  // 停止確認: 追加スクロール後もカード数不変、cursor リクエストも増えない
  const before = cursorResponses.length
  await o.page.mouse.wheel(0, 5000)
  await o.page.waitForTimeout(2000)
  expect(await posts.count()).toBe(TOTAL)
  expect(cursorResponses.length).toBe(before)
  await o.page.screenshot({ path: testInfo.outputPath('member-final.png'), fullPage: true })

  // 負/他テナント: URL 直打ち
  for (const [label, who] of [['同組織非メンバー', s], ['他テナント', t]] as const) {
    const apiRes = await who.ctx.request.get(`${API}/api/v1/timeline/feed?scopeType=TEAM&scopeId=${team.slug}&limit=2&cursor=${ids[TOTAL - 1]}`)
    console.log(`[UIR] ${label} API cursor feed -> ${apiRes.status()} ${(await apiRes.text()).slice(0, 160)}`)
    expect([403, 404]).toContain(apiRes.status())
    await who.page.goto(url, { waitUntil: 'domcontentloaded' })
    await waitForHydration(who.page)
    await waitIdle(who.page)
    await who.page.waitForTimeout(3000)
    const body = (await who.page.locator('body').innerText()).replace(/\s+/g, ' ')
    console.log(`[UIR] ${label} URL=${who.page.url()} text="${body.slice(0, 200)}"`)
    await who.page.screenshot({ path: testInfo.outputPath(`${label}.png`), fullPage: true })
    expect(body).not.toContain(tag)
    await who.page.goto(`/timeline/${ids[pinIdx]}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(who.page)
    await who.page.waitForTimeout(3000)
    const body2 = (await who.page.locator('body').innerText()).replace(/\s+/g, ' ')
    console.log(`[UIR] ${label} permalink URL=${who.page.url()} text="${body2.slice(0, 200)}"`)
    expect(body2).not.toContain(tag)
  }
  for (const c of ctxs) await c.close()
})
