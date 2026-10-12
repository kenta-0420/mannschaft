import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const pw = 'TestPass2026!'
// 閲覧メンバー(対象チームの MEMBER)。MEMBER は投稿できないため投稿とピン留めは pinner が行う
const owner = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: process.env.TEST_USER_PASSWORD ?? pw }
const pinner = { email: 'e2e-admin@test.mannschaft.local', password: pw }
// 対象チームと同じ組織配下の別チームに所属し、対象チームには入っていないユーザー
const sameOrgNonMember = { email: 'e2e-dummy-1@test.mannschaft.local', password: pw }
// 対象組織に直接所属せず、配下のチームにも入っていないユーザー
const otherTenant = { email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password: pw }
const TEAM_SLUG = 'e2e'
interface MyTeam { slug: string, organizationId: number | null, role: string }
interface MyOrg { id: number, role: string }
interface Session { ctx: BrowserContext, page: Page }

async function waitIdle(page: Page) {
  await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 30_000 })
}

test('チームTLのカーソルページング: ピン先頭1回・重複欠落なし・末尾で停止、同組織非メンバー/他テナントは閲覧不可', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const ctxs: BrowserContext[] = []
  const ids: number[] = []
  const cleanupErrors: string[] = []
  let a: Session | null = null
  const open = async (c: { email: string, password: string }): Promise<Session> => {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    ctxs.push(ctx)
    const page = await ctx.newPage()
    page.setDefaultTimeout(20_000)
    page.setDefaultNavigationTimeout(60_000)
    await loginViaApi(page, c, { apiBaseUrl: API })
    return { ctx, page }
  }
  const tag = `UIR-${Date.now()}`
  const TOTAL = 25
  const marker = (i: number) => `${tag}-P${String(i).padStart(2, '0')}`

  try {
    const o = await open(owner)
    const s = await open(sameOrgNonMember)
    const t = await open(otherTenant)
    a = await open(pinner)
    const pinnerSession = a

    // 前提: 対象チームの organizationId を導出し、各ロールの所属関係を API で実証する
    const myTeams = async (who: Session) => (await (await who.ctx.request.get(`${API}/api/v1/me/teams`)).json() as { data: MyTeam[] }).data
    const myOrgs = async (who: Session) => (await (await who.ctx.request.get(`${API}/api/v1/me/organizations`)).json() as { data: MyOrg[] }).data
    const target = (await myTeams(o)).find(x => x.slug === TEAM_SLUG)
    expect(target, '閲覧メンバーが対象チームの所属').toBeTruthy()
    const orgId = target!.organizationId
    expect(orgId, '対象チームは組織配下').not.toBeNull()
    const sTeams = await myTeams(s)
    expect(sTeams.some(x => x.organizationId === orgId && x.slug !== TEAM_SLUG), '非メンバーは同じ組織配下の別チームに所属').toBe(true)
    expect(sTeams.some(x => x.slug === TEAM_SLUG), '非メンバーは対象チームに所属しない').toBe(false)
    expect((await myOrgs(t)).some(x => x.id === orgId), '他テナントは対象組織に直接所属しない').toBe(false)
    expect((await myTeams(t)).some(x => x.organizationId === orgId), '他テナントは対象組織配下のチームに所属しない').toBe(false)

    for (let i = 0; i < TOTAL; i++) {
      const r = await pinnerSession.ctx.request.post(`${API}/api/v1/timeline/posts`, { data: { content: marker(i), scopeType: 'TEAM', scopeId: TEAM_SLUG } })
      expect(r.status(), await r.text()).toBe(201)
      ids.push((await r.json() as { data: { id: number } }).data.id)
    }
    const pinIdx = 3
    const pin = await pinnerSession.ctx.request.post(`${API}/api/v1/timeline/posts/${ids[pinIdx]}/pin?pinned=true`)
    expect(pin.status(), await pin.text()).toBe(200)

    // 正: 閲覧メンバー。共有チームの既存投稿があり得るため、照合は自分の tag のカードだけで行う
    const url = `/teams/${TEAM_SLUG}/timeline`
    await o.page.goto(url, { waitUntil: 'domcontentloaded' })
    await waitForHydration(o.page)
    const initialPermissions = o.page.getByTestId('member-permission-setup')
    if (await initialPermissions.isVisible()) {
      await initialPermissions.getByRole('button', { name: 'あとで決める' }).click()
    }
    const feed = o.page.getByTestId('timeline-feed')
    await expect(feed).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
    await waitIdle(o.page)
    const mine = feed.getByTestId('team-timeline-post').filter({ hasText: tag })
    const mineTexts = async () => (await mine.allInnerTexts()).map(x => new RegExp(`${tag}-P\\d{2}`).exec(x)?.[0] ?? '')
    const first = await mineTexts()
    // 他のピン投稿が先にあり得るため、全体先頭ではなく「自分の投稿群の中で先頭」とする
    expect(first[0], '自分の投稿群の先頭がピン留め投稿').toBe(marker(pinIdx))
    expect(await mine.first().innerText(), 'ピン表示がある').toContain('ピン留め')
    expect(await mine.filter({ hasText: 'ピン留め' }).count(), '自分の投稿でピン表示は1枚だけ').toBe(1)
    expect(first.filter(x => x === marker(pinIdx)), 'ピン留めは初回に1回だけ').toHaveLength(1)
    expect(first.length, '初回は全件ではない').toBeLessThan(TOTAL)

    const cursorStatuses: number[] = []
    const cursorRequests: string[] = []
    const isCursorFeed = (u: string) => {
      const x = new URL(u)
      return x.pathname === '/api/v1/timeline/feed' && x.searchParams.get('cursor') != null
    }
    o.page.on('request', (r) => { if (isCursorFeed(r.url())) cursorRequests.push(r.url()) })
    o.page.on('response', (r) => { if (isCursorFeed(r.url())) cursorStatuses.push(r.status()) })
    const allCards = feed.getByTestId('team-timeline-post')
    const loadMore = feed.getByTestId('timeline-load-more-target')
    for (let i = 0; i < 30 && await loadMore.count() > 0; i++) {
      const before = await allCards.count()
      const response = o.page.waitForResponse(r => isCursorFeed(r.url()), { timeout: 20_000 })
      await loadMore.scrollIntoViewIfNeeded()
      await response
      // 進行条件は全カード数の増加か末尾到達。tag は内容照合にだけ使う（共有チームの古い投稿でも詰まらない）
      await expect.poll(async () => (await allCards.count()) > before || (await loadMore.count()) === 0, { timeout: 20_000 }).toBe(true)
    }
    await expect(loadMore, '最後まで到達したら読み込みトリガが消える').toHaveCount(0)
    const all = await mineTexts()
    expect(new Set(all).size, '重複なし').toBe(all.length)
    expect([...all].sort(), '欠落なし').toEqual(ids.map((_, i) => marker(i)).sort())
    expect(cursorStatuses.length).toBeGreaterThan(0)
    expect(cursorStatuses.every(c => c === 200)).toBe(true)
    // 停止確認: 追加スクロールしても枚数不変で、cursor 付きリクエスト自体が発生しない
    const requestsBefore = cursorRequests.length
    await o.page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await o.page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    // 「何も起きないこと」の確認なので固定の観測期間で request イベントを数える
    await o.page.waitForTimeout(3_000)
    expect(await mine.count()).toBe(TOTAL)
    expect(cursorRequests.length).toBe(requestsBefore)
    await o.page.screenshot({ path: testInfo.outputPath('member-final.png'), fullPage: true })

    // 負/他テナント: API と URL 直打ち。拒否表示が出るのを待ってから tag が無いことを見る
    for (const [label, who] of [['同組織非メンバー', s], ['他テナント', t]] as const) {
      const apiRes = await who.ctx.request.get(`${API}/api/v1/timeline/feed?scopeType=TEAM&scopeId=${TEAM_SLUG}&limit=2&cursor=${ids[TOTAL - 1]}`)
      expect(apiRes.status(), await apiRes.text()).toBe(403)
      await who.page.goto(url, { waitUntil: 'domcontentloaded' })
      await waitForHydration(who.page)
      await expect(who.page.getByText('情報を取得できませんでした')).toBeVisible({ timeout: 30_000 })
      await waitIdle(who.page)
      await who.page.screenshot({ path: testInfo.outputPath(`${label}.png`), fullPage: true })
      expect(await who.page.locator('body').innerText()).not.toContain(tag)
      // 投稿詳細: #3761 で追加される timeline-post-not-found を待つ（#3761 のマージ後に実行する前提）
      await who.page.goto(`/timeline/${ids[pinIdx]}`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(who.page)
      await expect(who.page.getByTestId('timeline-post-not-found')).toBeVisible({ timeout: 30_000 })
      expect(await who.page.locator('body').innerText()).not.toContain(tag)
    }
  } finally {
    // 共有チーム e2e に投稿を残さない（ピン留め投稿も削除で消える）。全件試行し、失敗はまとめて最後に fail させる
    for (const id of [...ids].reverse()) {
      try {
        const r = await a!.ctx.request.delete(`${API}/api/v1/timeline/posts/${id}`)
        if (r.status() !== 204) cleanupErrors.push(`投稿 ${id}: HTTP ${r.status()}`)
      } catch (error) {
        cleanupErrors.push(`投稿 ${id}: ${String(error)}`)
      }
    }
    for (const c of ctxs) {
      try {
        await c.close()
      } catch (error) {
        cleanupErrors.push(`context close: ${String(error)}`)
      }
    }
    expect(cleanupErrors, `後始末失敗: ${cleanupErrors.join(' / ')}`).toEqual([])
  }
})
