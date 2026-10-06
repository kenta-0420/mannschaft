import { expect, request, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

/** AC-1〜5: 前提と後始末のみAPIを使い、記事閲覧・編集入口は実際の一覧UIから操作する。 */
/**
 * 実機の前提: 4アカウントは独立IDかつ全員が非SYSTEM_ADMINであること。
 * 既定seedはe2e-adminにSYSTEM_ADMINを付けるため、そのまま本specへ流用しない。
 * 通常環境では非SYSTEM_ADMINのTEST_ADMIN_EMAIL等を指定する。今回の専用DBではseed後に
 * 4人のuser_roles JOIN rolesのSYSTEM_ADMIN件数0をDBで証明し、実APIのsystemRoleと照合する。
 * systemRoleはUserProfileResponseのnullable項目で、UserServiceが非管理者にはnullを生成する。
 * HTTPプロフィールだけをDBのロール不在証明の代用にはしない。
 */
test.use({ storageState: { cookies: [], origins: [] } })
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const PASSWORD = process.env.TEST_PASSWORD ?? process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const EMAILS = {
  owner: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  reader: process.env.TEST_BLOG_READER_EMAIL ?? 'e2e-supporter@test.mannschaft.local',
  admin: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  other: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
}

interface UserSession { context: BrowserContext; page: Page; id: number; email: string }
interface Scope { kind: 'teams' | 'organizations'; numericId: number; slug: string }
interface WirePost {
  id: number
  scope: { teamId?: number; organizationId?: number; userId?: number; authorId?: number }
  content: { title: string; slug: string; body: string }
  meta: { status: string }
}
interface Cleanup { email: string; path: string }
let pendingCleanup: Cleanup[] = []
let pendingUsers: UserSession[] = []
let cleanupLedgerPath = ''

function trackCleanup(cleanup: Cleanup[], item: Cleanup): void {
  cleanup.push(item)
  writeFileSync(cleanupLedgerPath, JSON.stringify({ pending: cleanup }, null, 2))
}

test.afterEach(async ({ browser: _browser }, testInfo) => {
  const contexts = new Map<string, APIRequestContext>()
  const results: { path: string; status?: number; error?: string }[] = []
  // 本人 editor の自動保存を止めてから前提データを削除する。close 失敗でも削除は続ける。
  const closed = await Promise.allSettled(pendingUsers.map(user => user.page.close()))
  const closeErrors = closed.flatMap((result, index) => result.status === 'rejected'
    ? [{ userId: pendingUsers[index]!.id, error: (String(result.reason).split('\n')[0] ?? '').replace(/eyJ[A-Za-z0-9_.-]+/g, '[token redacted]') }]
    : [])
  try {
    // ブラウザーのタイムアウトで破棄された request を再利用せず、後始末専用に認証する。
    for (const item of [...pendingCleanup].reverse()) {
      try {
        let api = contexts.get(item.email)
        if (!api) {
          api = await request.newContext({ baseURL: API_BASE })
          contexts.set(item.email, api)
          const login = await api.post('/api/v1/auth/login', { data: { email: item.email, password: PASSWORD } })
          expect(login.status(), '後始末専用の認証').toBe(200)
        }
        const response = await api.delete(item.path)
        results.push({ path: item.path, status: response.status() })
      } catch (error) {
        // Playwright のエラー全文には Cookie ヘッダーが含まれるため、先頭の理由だけ保存する。
        results.push({ path: item.path, error: (String(error).split('\n')[0] ?? '').replace(/eyJ[A-Za-z0-9_.-]+/g, '[token redacted]') })
      }
      writeFileSync(cleanupLedgerPath, JSON.stringify({ pending: pendingCleanup, results, closeErrors }, null, 2))
    }
    await testInfo.attach('blog-list-cleanup', { path: cleanupLedgerPath, contentType: 'application/json' })
    // afterEach の失敗は元のテスト失敗に追加され、失敗箇所を finally の例外で置き換えない。
    expect(results.filter(result => result.status !== 204), '前提データの削除成功').toEqual([])
    expect(closeErrors, 'UI の自動保存タイマー停止').toEqual([])
  } finally {
    await Promise.allSettled([...contexts.values()].map(api => api.dispose()))
    await Promise.allSettled(pendingUsers.map(user => user.context.close()))
    pendingCleanup = []
    pendingUsers = []
  }
})

async function openUser(browser: Browser, baseURL: string, email: string): Promise<UserSession> {
  const context = await browser.newContext({ baseURL, locale: 'ja-JP', storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  try {
    await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
    const me = await page.request.get(`${API_BASE}/api/v1/users/me`)
    expect(me.status(), '前提の利用者プロフィール取得').toBe(200)
    const profile = (await me.json()).data
    expect(Number.isSafeInteger(profile.id)).toBe(true)
    expect(profile.id).toBeGreaterThan(0)
    expect(profile, '実機ロール横断はSYSTEM_ADMINの迂回で成立させない').toHaveProperty('systemRole', null)
    return { context, page, id: profile.id, email }
  } catch (error) {
    await context.close()
    throw error
  }
}

async function createScope(user: UserSession, kind: Scope['kind'], slug: string, cleanup: Cleanup[]): Promise<Scope> {
  const response = await user.page.request.post(`${API_BASE}/api/v1/${kind}`, {
    data: { name: slug, slug, visibility: 'PUBLIC', ...(kind === 'organizations' ? { orgType: 'COMMUNITY' } : {}) },
  })
  expect(response.status(), `専用${kind}作成: ${await response.text()}`).toBe(201)
  const scope: Scope = { ...(await response.json()).data, kind }
  trackCleanup(cleanup, { email: user.email, path: `/api/v1/${kind}/${scope.slug}` })
  expect(Number.isSafeInteger(scope.numericId)).toBe(true)
  expect(scope.numericId).toBeGreaterThan(0)
  return scope
}

async function joinAsMember(admin: UserSession, member: UserSession, scope: Scope, cleanup: Cleanup[]): Promise<void> {
  const invitePath = `/api/v1/${scope.kind}/${scope.slug}/invite-tokens`
  const response = await admin.page.request.post(`${API_BASE}${invitePath}`, {
    data: { roleId: Number(process.env.E2E_MEMBER_ROLE_ID ?? '4'), maxUses: 1, expiresIn: '1d' },
  })
  expect(response.status(), `MEMBER前提招待: ${await response.text()}`).toBe(201)
  const invitation = (await response.json()).data
  trackCleanup(cleanup, { email: admin.email, path: `${invitePath}/${invitation.id}` })
  const joined = await member.page.request.post(`${API_BASE}/api/v1/invite/${invitation.token}/join`)
  expect(joined.status(), `正規MEMBER加入: ${await joined.text()}`).toBe(200)
  trackCleanup(cleanup, { email: member.email, path: `/api/v1/${scope.kind}/${scope.slug}/me` })
  await assertRole(member, scope, 'MEMBER')
}

async function assertRole(user: UserSession, scope: Scope, role: string): Promise<void> {
  const response = await user.page.request.get(`${API_BASE}/api/v1/${scope.kind}/${scope.slug}/me/permissions`)
  expect(response.status(), '前提の実効スコープロール').toBe(200)
  expect((await response.json()).data.roleName).toBe(role)
}

async function createPost(user: UserSession, scope: Scope, title: string, slug: string, body: string, publish: boolean, cleanup: Cleanup[]): Promise<WirePost> {
  const response = await user.page.request.post(`${API_BASE}/api/v1/blog/posts`, {
    data: {
      [scope.kind === 'teams' ? 'teamId' : 'organizationId']: String(scope.numericId),
      title, slug, body, visibility: 'MEMBERS_ONLY', postType: 'BLOG', crossPostToTimeline: false,
    },
  })
  expect(response.status(), `記事前提作成: ${await response.text()}`).toBe(201)
  const post: WirePost = (await response.json()).data
  trackCleanup(cleanup, { email: user.email, path: `/api/v1/blog/posts/${post.id}` })
  expect(post).not.toHaveProperty('author')
  expect(post.scope.authorId).toBe(user.id)
  expect(post.content.slug).toBe(slug)
  expect(post.meta.status).toBe('DRAFT')
  if (publish) {
    const published = await user.page.request.patch(`${API_BASE}/api/v1/blog/posts/${post.id}/publish`, { data: { status: 'PUBLISHED' } })
    expect(published.status(), `公開記事の前提: ${await published.text()}`).toBe(200)
    const publishedPost: WirePost = (await published.json()).data
    expect(publishedPost.meta.status).toBe('PUBLISHED')
    expect(publishedPost).not.toHaveProperty('author')
    return publishedPost
  }
  return post
}

async function openList(page: Page, scope: Scope): Promise<WirePost[]> {
  const queryKey = scope.kind === 'teams' ? 'teamId' : 'organizationId'
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/blog/posts'
      && url.searchParams.get(queryKey) === scope.slug
  })
  await page.goto(`/${scope.kind}/${scope.slug}/blog`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(page.locator('header')).toBeVisible()
  const response = await responsePromise
  expect(response.status(), '実際の一覧UIが呼ぶAPI').toBe(200)
  const posts: WirePost[] = (await response.json()).data
  expect(posts.length).toBeGreaterThan(0)
  for (const post of posts) expect(post, '現行HTTPレスポンスのauthor省略').not.toHaveProperty('author')
  await expect(page.getByTestId('blog-post-card').filter({ has: page.getByRole('heading', { level: 3, name: posts[0]!.content.title, exact: true }) })).toBeVisible()
  return posts
}

async function createPersonalPost(owner: UserSession, slug: string, cleanup: Cleanup[]): Promise<WirePost> {
  const response = await owner.page.request.post(`${API_BASE}/api/v1/users/me/blog/posts`, {
    data: { title: `個人記事 ${slug}`, slug, body: `個人本文 ${slug}`, visibility: 'PUBLIC', postType: 'BLOG', crossPostToTimeline: false },
  })
  expect(response.status(), `個人記事の前提作成: ${await response.text()}`).toBe(201)
  const post: WirePost = (await response.json()).data
  trackCleanup(cleanup, { email: owner.email, path: `/api/v1/users/me/blog/posts/${post.id}` })
  expect(post).not.toHaveProperty('author')
  expect(post.scope.userId).toBe(owner.id)
  expect(post.scope.authorId).toBe(owner.id)
  expect(post.content.slug).toBe(slug)
  const published = await owner.page.request.patch(`${API_BASE}/api/v1/users/me/blog/posts/${post.id}/publish`, { data: { status: 'PUBLISHED' } })
  expect(published.status(), `個人記事の公開前提: ${await published.text()}`).toBe(200)
  const publishedPost: WirePost = (await published.json()).data
  expect(publishedPost.meta.status).toBe('PUBLISHED')
  expect(publishedPost).not.toHaveProperty('author')
  return publishedPost
}

async function readPersonalFromList(reader: UserSession, ownerId: number, post: WirePost): Promise<void> {
  const editReads: string[] = []
  const onRequest = (request: import('@playwright/test').Request) => {
    if (/\/api\/v1\/users\/me\/blog\/posts\/\d+(?:\/|$)/.test(new URL(request.url()).pathname)) editReads.push(request.url())
  }
  reader.page.on('request', onRequest)
  try {
    const listResponse = reader.page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1/users/${ownerId}/blog/posts`)
    await reader.page.goto(`/users/${ownerId}/blog`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(reader.page)
    await expect(reader.page.locator('header')).toBeVisible()
    const response = await listResponse
    expect(response.status(), 'userId 限定の個人一覧 API').toBe(200)
    const posts: WirePost[] = (await response.json()).data
    expect(posts.some(item => item.id === post.id)).toBe(true)
    for (const item of posts) {
      expect(item.scope.userId).toBe(ownerId)
      expect(item).not.toHaveProperty('author')
    }
    const titleLink = reader.page.getByRole('link').filter({ has: reader.page.getByRole('heading', { name: post.content.title, exact: true, level: 3 }) })
    const detailResponse = reader.page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/v1/users/${ownerId}/blog/posts/${post.content.slug}`)
    await titleLink.click()
    const detail = await detailResponse
    expect(detail.status(), '個人一覧タイトルから本文取得').toBe(200)
    const detailPost: WirePost = (await detail.json()).data
    expect(detailPost.id).toBe(post.id)
    expect(detailPost).not.toHaveProperty('author')
    await expect(reader.page).toHaveURL(url => url.pathname === `/users/${ownerId}/blog/posts/${post.content.slug}`)
    await expect(reader.page.getByRole('heading', { name: post.content.title, exact: true, level: 1 })).toBeVisible()
    await expect(reader.page.locator('article .prose')).toContainText(post.content.body)
    expect(editReads, '個人記事の閲覧でも本人編集 API を呼ばない').toEqual([])
  } finally {
    reader.page.off('request', onRequest)
  }
}

async function readFromList(user: UserSession, scope: Scope, post: WirePost, body: string): Promise<void> {
  const editReads: string[] = []
  const onRequest = (request: import('@playwright/test').Request) => {
    if (/\/api\/v1\/users\/me\/blog\/posts\/\d+(?:\/|$)/.test(new URL(request.url()).pathname)) editReads.push(request.url())
  }
  user.page.on('request', onRequest)
  try {
    const posts = await openList(user.page, scope)
    expect(posts.find(item => item.id === post.id)?.scope.authorId).toBe(post.scope.authorId)
    const read = user.page.getByTestId(`blog-post-read-${post.id}`)
    await expect(read).toHaveText(post.content.title)
    if (user.id !== post.scope.authorId) {
      await expect(user.page.getByTestId(`blog-post-edit-${post.id}`)).toHaveCount(0)
    } else {
      await expect(user.page.getByTestId(`blog-post-edit-${post.id}`)).toBeVisible()
    }
    const scopeQuery = scope.kind === 'teams' ? 'teamId' : 'organizationId'
    const responsePromise = user.page.waitForResponse(response => {
      const url = new URL(response.url())
      return response.request().method() === 'GET' && url.pathname === `/api/v1/blog/posts/${post.content.slug}`
        && url.searchParams.get(scopeQuery) === String(scope.numericId)
    })
    await read.click()
    const response = await responsePromise
    expect(response.status(), 'タイトルクリックから本文取得').toBe(200)
    const detail: WirePost = (await response.json()).data
    expect(detail.id).toBe(post.id)
    expect(detail).not.toHaveProperty('author')
    expect(detail.content.body).toContain(body)
    await expect(user.page).toHaveURL(url => url.pathname === `/blog/posts/${post.content.slug}` && url.searchParams.get(scopeQuery) === String(scope.numericId))
    await expect(user.page.getByRole('heading', { name: post.content.title, level: 1 })).toBeVisible()
    await expect(user.page.locator('article .prose')).toContainText(body)
    expect(editReads, '閲覧操作が本人専用編集APIへ入らない').toEqual([])
  } finally {
    user.page.off('request', onRequest)
  }
}

test('BLOG-LIST-REAL: タイトル閲覧・本人編集・他作者と別テナントへの拒否', async ({ browser, baseURL }, testInfo) => {
  test.setTimeout(300_000)
  cleanupLedgerPath = testInfo.outputPath('blog-list-cleanup-ledger.json')
  writeFileSync(cleanupLedgerPath, JSON.stringify({ pending: [] }, null, 2))
  expect(baseURL, '実機FEのbaseURLを指定').toBeTruthy()
  expect(new Set(Object.values(EMAILS)).size, '4種類の独立利用者を使用').toBe(4)
  const users: UserSession[] = []
  const cleanup: Cleanup[] = []
  pendingUsers = users
  pendingCleanup = cleanup
  const stamp = Date.now().toString(36)
  const owner = await openUser(browser, baseURL!, EMAILS.owner); users.push(owner)
  const reader = await openUser(browser, baseURL!, EMAILS.reader); users.push(reader)
  const admin = await openUser(browser, baseURL!, EMAILS.admin); users.push(admin)
  const other = await openUser(browser, baseURL!, EMAILS.other); users.push(other)
  expect(new Set(users.map(user => user.id)).size).toBe(4)
  const team = await createScope(admin, 'teams', `blog-read-${stamp}`, cleanup)
  const org = await createScope(admin, 'organizations', `blog-org-${stamp}`, cleanup)
  const otherTeam = await createScope(other, 'teams', `blog-other-${stamp}`, cleanup)
  for (const scope of [team, org]) {
    await assertRole(admin, scope, 'ADMIN')
    await joinAsMember(admin, owner, scope, cleanup)
    await joinAsMember(admin, reader, scope, cleanup)
  }
  const sharedSlug = `blog-list-${stamp}`
  const teamBody = `Team本文-${stamp}`
  const orgBody = `Org本文-${stamp}`
  const teamPost = await createPost(admin, team, `Team記事-${stamp}`, sharedSlug, teamBody, true, cleanup)
  const orgPost = await createPost(admin, org, `Org記事-${stamp}`, sharedSlug, orgBody, true, cleanup)
  const draftBody = `本人下書き本文-${stamp}`
  const draft = await createPost(owner, team, `本人下書き-${stamp}`, `${sharedSlug}-draft`, draftBody, false, cleanup)
  const privateBody = `別テナント本文-${stamp}`
  const privatePost = await createPost(other, otherTeam, `別テナント記事-${stamp}`, `${sharedSlug}-private`, privateBody, true, cleanup)
  const personalPost = await createPersonalPost(owner, `${sharedSlug}-personal`, cleanup)
  await testInfo.attach('blog-list-http-fixtures', {
    body: JSON.stringify({ userIds: users.map(user => user.id), team, org, otherTeam, teamPost, orgPost, draft, privatePost, personalPost }),
    contentType: 'application/json',
  })

  // 同slugのTEAM/ORG記事を、本人とは別の正規MEMBERとADMINも実一覧から読む。
  for (const user of [owner, reader, admin]) {
    await readFromList(user, team, teamPost, teamBody)
    await readFromList(user, org, orgPost, orgBody)
  }

  // 個人一覧は既存の専用 NuxtLink 実装。BlogPostList の PERSONAL 分岐は unit で保証し、
  // 実機では userId 限定一覧から既存 reader への接続と本文取得を補完する。
  await readPersonalFromList(reader, owner.id, personalPost)
  await openList(owner.page, team)
  await expect(owner.page.getByTestId(`blog-post-read-${draft.id}`)).toHaveCount(0)
  const ownLoad = owner.page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/users/me/blog/posts/${draft.id}` && response.request().method() === 'GET')
  await owner.page.getByTestId(`blog-post-edit-${draft.id}`).click()
  expect((await ownLoad).status(), '本人の明示編集').toBe(200)
  await expect(owner.page).toHaveURL(url => url.pathname === `/blog/posts/${draft.id}/edit`)
  await expect(owner.page.locator('textarea.editor-textarea')).toHaveValue(draftBody)
  await expect(owner.page.getByRole('button', { name: '保存', exact: true })).toBeVisible()
  await openList(owner.page, team) // 本人エディタの自動保存タイマーを終了させる。

  // 新規作成の既存「作成→編集」導線も、対象操作をUIから実行して維持を確認する。
  await owner.page.getByTestId('blog-post-create-button').click()
  const dialog = owner.page.getByRole('dialog')
  const newTitle = `UI作成記事-${stamp}`
  await dialog.locator('input').fill(newTitle)
  const createdResponse = owner.page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/blog/posts')
  const newEditLoad = owner.page.waitForResponse(response => response.request().method() === 'GET' && /\/api\/v1\/users\/me\/blog\/posts\/\d+$/.test(new URL(response.url()).pathname))
  await owner.page.getByTestId('blog-post-create-submit').click()
  const created = await createdResponse
  expect(created.status(), '一覧の新規作成操作').toBe(201)
  const newPost: WirePost = (await created.json()).data
  trackCleanup(cleanup, { email: owner.email, path: `/api/v1/blog/posts/${newPost.id}` })
  expect(newPost).not.toHaveProperty('author')
  expect(newPost.scope.authorId).toBe(owner.id)
  const editorResponse = await newEditLoad
  expect(editorResponse.status(), '作成後の既存編集読込み').toBe(200)
  expect(new URL(editorResponse.url()).pathname).toBe(`/api/v1/users/me/blog/posts/${newPost.id}`)
  await expect(owner.page).toHaveURL(url => url.pathname === `/blog/posts/${newPost.id}/edit`)
  await expect(owner.page.locator('input[placeholder="タイトルを入力してください"]')).toHaveValue(newTitle)
  await expect(owner.page.locator('textarea.editor-textarea')).toHaveValue('')
  await openList(owner.page, team)

  const writes: string[] = []
  reader.page.on('request', request => {
    if (['PUT', 'PATCH'].includes(request.method()) && new URL(request.url()).pathname.includes(`/blog/posts/${teamPost.id}`)) writes.push(request.url())
  })
  const deniedLoad = reader.page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/users/me/blog/posts/${teamPost.id}` && response.request().method() === 'GET')
  await reader.page.goto(`/blog/posts/${teamPost.id}/edit`, { waitUntil: 'domcontentloaded' })
  expect((await deniedLoad).status(), '他作者の記事ID直打ちは存在を秘匿').toBe(404)
  await expect(reader.page.getByTestId('blog-load-error')).toContainText('記事を読み込めませんでした')
  await expect(reader.page.locator('textarea.editor-textarea')).toHaveCount(0)
  await expect(reader.page.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
  await expect(reader.page.locator('#autosave-toggle')).toHaveCount(0)
  await reader.page.bringToFront()
  expect(await reader.page.evaluate(() => document.visibilityState)).toBe('visible')
  await reader.page.getByTestId('blog-load-error').locator('..').locator('button').first().focus()
  await reader.page.keyboard.press('Control+s')
  // エディタの30秒自動保存周期を実時間で1回観測し、読み込み拒否後の書込みが無いことを確認する。
  await reader.page.waitForTimeout(31_000)
  expect(writes, '読み込み拒否後はCtrl+S・自動保存とも送信しない').toEqual([])

  const privateLoad = reader.page.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/blog/posts/${privatePost.content.slug}` && response.request().method() === 'GET')
  await reader.page.goto(`/blog/posts/${privatePost.content.slug}?teamId=${otherTeam.numericId}`, { waitUntil: 'domcontentloaded' })
  expect([403, 404], '別テナントの限定記事は拒否').toContain((await privateLoad).status())
  await expect(reader.page.locator('article .prose')).toHaveCount(0)
  await expect(reader.page.getByText(privateBody, { exact: true })).toHaveCount(0)
})
