import { expect, test, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const ownerCredentials = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}
const outsiderCredentials = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? 'TestPass2026!',
}

interface Team { slug: string, numericId: number }
interface Post { id: number, content?: { text?: string } }
interface Feed {
  data: { pinned: Post[], posts: Post[] }
  meta: { nextCursor: number | null, limit: number, hasNext: boolean }
}

async function createPost(request: APIRequestContext, scopeType: 'PUBLIC' | 'TEAM', scopeId: string, marker: string): Promise<number> {
  const response = await request.post(`${API}/api/v1/timeline/posts`, {
    data: { content: marker, scopeType, scopeId },
  })
  expect(response.status(), `投稿作成 ${marker}: ${await response.text()}`).toBe(201)
  const body = await response.json() as { data: { id: number } }
  return body.data.id
}

async function getFeed(request: APIRequestContext, scopeType: 'PUBLIC' | 'TEAM', scopeId: string, limit: number, cursor?: number): Promise<Feed> {
  const query = new URLSearchParams({ scopeType, scopeId, limit: String(limit) })
  if (cursor != null) query.set('cursor', String(cursor))
  const response = await request.get(`${API}/api/v1/timeline/feed?${query}`)
  expect(response.status(), `フィード取得 ${query}: ${await response.text()}`).toBe(200)
  return await response.json() as Feed
}

async function deletePost(request: APIRequestContext, id: number): Promise<string | null> {
  try {
    const response = await request.delete(`${API}/api/v1/timeline/posts/${id}`)
    return response.status() === 204 ? null : `投稿 ${id}: HTTP ${response.status()}`
  } catch (error) {
    return `投稿 ${id}: ${String(error)}`
  }
}

test('CMP-019 Wave8: 公開フィードのカーソル、ピン分離、チーム画面の追加ロードと越境拒否', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const owner: BrowserContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const outsider: BrowserContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const anonymous: BrowserContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const ownerPage: Page = await owner.newPage()
  const outsiderPage: Page = await outsider.newPage()
  const anonymousPage: Page = await anonymous.newPage()
  for (const page of [ownerPage, outsiderPage, anonymousPage]) {
    page.setDefaultTimeout(15_000)
    page.setDefaultNavigationTimeout(30_000)
  }
  const createdIds: number[] = []
  const cleanupErrors: string[] = []
  const tag = `CMP019-W8-${Date.now()}-${crypto.randomUUID().slice(0, 8)}`
  let team: Team | null = null

  try {
    await loginViaApi(ownerPage, ownerCredentials, { apiBaseUrl: API })
    await loginViaApi(outsiderPage, outsiderCredentials, { apiBaseUrl: API })
    console.log('[W8-REAL] login completed')

    const createdTeam = await owner.request.post(`${API}/api/v1/teams`, {
      data: { name: `w8-${tag}`, visibility: 'MEMBERS_AND_ABOVE' },
    })
    expect(createdTeam.status(), `使い捨てチーム作成: ${await createdTeam.text()}`).toBe(201)
    team = (await createdTeam.json() as { data: Team }).data
    expect(team.slug).toBeTruthy()
    const teamDetail = await owner.request.get(`${API}/api/v1/teams/${team.slug}`)
    expect(teamDetail.status(), `作成者によるチーム詳細取得: ${await teamDetail.text()}`).toBe(200)

    const pageRequests: string[] = []
    ownerPage.on('request', (request) => {
      if (request.url().includes('/api/v1/teams/')) pageRequests.push(`SEND ${request.url()}`)
    })
    ownerPage.on('response', (response) => {
      if (response.url().includes('/api/v1/teams/')) pageRequests.push(`${response.status()} ${response.url()}`)
    })
    ownerPage.on('requestfailed', (request) => {
      if (request.url().includes('/api/v1/teams/')) pageRequests.push(`FAIL ${request.url()} ${request.failure()?.errorText}`)
    })
    await ownerPage.goto(`/teams/${team.slug}/timeline`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(ownerPage)
    try {
      await expect(ownerPage.getByTestId('team-timeline-composer')).toBeVisible({ timeout: 10_000 })
    } catch (error) {
      throw new Error(`作成直後のチーム画面: ${ownerPage.url()} / ${pageRequests.join(', ')}`, { cause: error })
    }
    const initialPermissions = ownerPage.getByTestId('member-permission-setup')
    await expect(initialPermissions, '新規チームの権限初期設定案内').toBeVisible({ timeout: 15_000 })
    await initialPermissions.getByRole('button', { name: 'あとで決める' }).click()
    await expect(initialPermissions).toBeHidden()
    console.log('[W8-REAL] disposable team page opened')

    // 公開投稿は他のテストデータと共存するため、今回作成した ID で連続性を検証する。
    const publicIds: number[] = []
    for (let i = 0; i < 6; i++) {
      const id = await createPost(owner.request, 'PUBLIC', '0', `${tag}-PUBLIC-${i}`)
      publicIds.push(id)
      createdIds.push(id)
    }
    const pin = await owner.request.post(`${API}/api/v1/timeline/posts/${publicIds[0]!}/pin?pinned=true`)
    expect(pin.status(), `公開投稿ピン留め: ${await pin.text()}`).toBe(200)

    const first = await getFeed(owner.request, 'PUBLIC', '0', 2)
    expect(first.data.posts, '公開フィード初回の取得件数').toHaveLength(2)
    expect(first.data.posts[0]!.id, '初回ページはID降順').toBeGreaterThan(first.data.posts[1]!.id)
    expect(first.data.pinned.map(post => post.id), 'ピン投稿は通常投稿と別枠').toContain(publicIds[0])
    expect(first.data.posts.map(post => post.id), 'ピン投稿が通常投稿に重複しない').not.toContain(publicIds[0])
    expect(first.meta).toMatchObject({ limit: 2, hasNext: true, nextCursor: first.data.posts[1]!.id })

    // ページ間で新規投稿と削除があっても、カーソルより古い未削除投稿を一度ずつ返す。
    const inserted = await createPost(owner.request, 'PUBLIC', '0', `${tag}-PUBLIC-inserted`)
    createdIds.push(inserted)
    const deleted = await owner.request.delete(`${API}/api/v1/timeline/posts/${publicIds[2]!}`)
    expect(deleted.status(), '途中削除').toBe(204)
    createdIds.splice(createdIds.indexOf(publicIds[2]!), 1)

    const second = await getFeed(owner.request, 'PUBLIC', '0', 2, first.meta.nextCursor!)
    expect(second.data.posts, '途中挿入・削除後の次ページ件数').toHaveLength(2)
    expect(second.data.posts[0]!.id, '次ページもID降順').toBeGreaterThan(second.data.posts[1]!.id)
    expect(second.data.posts.every(post => post.id < first.meta.nextCursor!), '次ページはカーソルより古い投稿だけ').toBe(true)
    expect(second.data.pinned, 'カーソルページではピン一覧を繰り返さない').toEqual([])
    expect(second.data.posts.map(post => post.id)).not.toContain(inserted)
    expect(second.data.posts.map(post => post.id)).not.toContain(publicIds[2])
    expect(new Set([...first.data.posts, ...second.data.posts].map(post => post.id)).size).toBe(4)
    console.log('[W8-REAL] public cursor and pin assertions completed')

    // 使い捨て TEAM なら既存投稿の混入がなく、20件ちょうどと追加ロードを確定的に試せる。
    const teamIds: number[] = []
    for (let i = 0; i < 21; i++) {
      const id = await createPost(owner.request, 'TEAM', team.slug, `${tag}-TEAM-${i}`)
      teamIds.push(id)
      createdIds.push(id)
    }
    const exact = await getFeed(owner.request, 'TEAM', team.slug, 2, teamIds[2]!)
    expect(exact.data.posts.map(post => post.id), '残り2件ちょうどの最終ページ').toEqual([teamIds[1], teamIds[0]])
    expect(exact.meta, '件数ぴったりなら余分なページを出さない').toMatchObject({ limit: 2, hasNext: false, nextCursor: null })
    console.log('[W8-REAL] team 21 posts and exact-limit assertions completed')

    const feedResponses: Array<{ status: number, cursor: string | null }> = []
    ownerPage.on('response', (response) => {
      const url = new URL(response.url())
      if (url.pathname === '/api/v1/timeline/feed' && url.searchParams.get('scopeType') === 'TEAM') {
        feedResponses.push({ status: response.status(), cursor: url.searchParams.get('cursor') })
      }
    })
    await ownerPage.goto(`/teams/${team.slug}/timeline`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(ownerPage)
    const feed = ownerPage.getByTestId('timeline-feed')
    await expect(feed).toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
    await expect(feed.getByTestId('team-timeline-post')).toHaveCount(20)
    console.log('[W8-REAL] team feed initial 20 visible')
    await feed.getByTestId('timeline-load-more-target').scrollIntoViewIfNeeded()
    await expect(feed.getByTestId('team-timeline-post'), '画面スクロールで残り1件が追加される').toHaveCount(21, { timeout: 60_000 })
    await expect(feed.getByText(`${tag}-TEAM-0`, { exact: true })).toBeVisible()
    expect(feedResponses.some(item => item.status === 200 && item.cursor != null), '実BEへのカーソル付き追加リクエスト').toBe(true)
    console.log('[W8-REAL] team feed 21 visible, cursor request 200')
    await ownerPage.screenshot({ path: testInfo.outputPath('team-feed-after-load.png') })
    console.log('[W8-REAL] screenshot saved; clicking oldest permalink')
    const permalink = feed.getByTestId('team-timeline-post')
      .filter({ hasText: `${tag}-TEAM-0` })
      .getByTestId('timeline-post-permalink')
    await expect(permalink).toBeVisible({ timeout: 15_000 })
    await Promise.all([
      ownerPage.waitForURL(new RegExp(`/timeline/${teamIds[0]!}$`), { timeout: 15_000, waitUntil: 'commit' }),
      permalink.click({ timeout: 15_000, noWaitAfter: true }),
    ])
    await expect(ownerPage).toHaveURL(new RegExp(`/timeline/${teamIds[0]!}$`))
    await expect(ownerPage.getByText(`${tag}-TEAM-0`, { exact: true })).toBeVisible()
    console.log('[W8-REAL] oldest permalink opened')

    // 他アカウントは非公開チームの投稿を API と URL 直打ちの双方から読めない。
    const forbidden = await outsider.request.get(`${API}/api/v1/timeline/feed?scopeType=TEAM&scopeId=${team.slug}&limit=2`)
    expect([403, 404], `他アカウントのTEAM feed: ${await forbidden.text()}`).toContain(forbidden.status())
    await outsiderPage.goto(`/teams/${team.slug}/timeline`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(outsiderPage)
    await expect(outsiderPage.getByText(`${tag}-TEAM-20`, { exact: true })).toHaveCount(0)
    await outsiderPage.goto(`/timeline/${teamIds[20]!}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(outsiderPage)
    await expect(outsiderPage.getByText(`${tag}-TEAM-20`, { exact: true })).toHaveCount(0)
    console.log('[W8-REAL] outsider API and UI denied')

    const unauthenticated = await anonymous.request.get(`${API}/api/v1/timeline/feed?scopeType=TEAM&scopeId=${team.slug}&limit=2`)
    expect(unauthenticated.status(), '未ログインのTEAM feed').toBe(401)
    await anonymousPage.goto(`/teams/${team.slug}/timeline`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(anonymousPage)
    await expect(anonymousPage.getByText(`${tag}-TEAM-20`, { exact: true })).toHaveCount(0)
    console.log('[W8-REAL] anonymous API and UI denied')
  } finally {
    console.log(`[W8-REAL] cleanup starting: ${createdIds.length} posts`)
    for (const id of createdIds.reverse()) {
      const error = await deletePost(owner.request, id)
      if (error) cleanupErrors.push(error)
    }
    console.log('[W8-REAL] post cleanup completed')
    if (team) {
      try {
        const response = await owner.request.delete(`${API}/api/v1/teams/${team.slug}`)
        if (response.status() !== 204) cleanupErrors.push(`チーム ${team.slug}: HTTP ${response.status()}`)
      } catch (error) {
        cleanupErrors.push(`チーム ${team.slug}: ${String(error)}`)
      }
    }
    await owner.close()
    await outsider.close()
    await anonymous.close()
    console.log('[W8-REAL] browser contexts closed')
    expect(cleanupErrors, `後始末失敗:\n${cleanupErrors.join('\n')}`).toEqual([])
  }
})
