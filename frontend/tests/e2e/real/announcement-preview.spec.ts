import { test, expect, request as playwrightRequest, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import ja from '../../../app/locales/ja/announcement.json' with { type: 'json' }
import en from '../../../app/locales/en/announcement.json' with { type: 'json' }
import de from '../../../app/locales/de/announcement.json' with { type: 'json' }
import es from '../../../app/locales/es/announcement.json' with { type: 'json' }
import ko from '../../../app/locales/ko/announcement.json' with { type: 'json' }
import zh from '../../../app/locales/zh/announcement.json' with { type: 'json' }

/** 実API＋UI操作の本文プレビュー。route mock・本文APIの直接代替呼び出しは使わない。 */
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const TEAM_SLUG = process.env.E2E_PREVIEW_TEAM_SLUG ?? 'fc-u-18'
let ORG_ID: number
const PASSWORD = process.env.TEST_PASSWORD ?? 'TestPass2026!'
const ADMIN = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const SUPPORTER = process.env.TEST_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local'
const stamp = Date.now()
const marker = `PreviewBody-${stamp}`
const bulletinMarker = `PreviewBulletin-${stamp}`
const blogTitle = `PreviewBlog-${stamp}-${'長い見出し'.repeat(18)}`
const bulletinTitle = `PreviewThread-${stamp}`
type Fixture = { announcementFeedId: number; contentId: number; channel: 'BLOG_POST' | 'BULLETIN_THREAD'; scopeId: number; scopeType: 'TEAM' | 'ORGANIZATION' }
let api: APIRequestContext
let fixtureHeaders: Record<string, string>
let teamId: number
let blog: Fixture
let bulletin: Fixture
let otherBulletin: Fixture
let blogUrl = ''
let bulletinUrl = ''
let otherTeamSlug = ''
let orgSlug = ''
let orgBlog: Fixture
let orgBulletin: Fixture
const normalBlogs: Fixture[] = []
let attachmentId: number
let ownOrgMemberUserId: number | undefined
let ownOrgInviteId: number | undefined
let ownOrgCreated = false
let ownAffiliationId: number | undefined
let previousTeamOrganizationSlugs: string[] = []
const created: Fixture[] = []
const PNG_BYTES = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')

async function login(page: Page, email = MEMBER): Promise<void> {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
}
function card(page: Page, id: number) {
  return page.locator(`[data-announcement-id="${id}"]`)
}
async function organizationTabs(headers: Record<string, string>) {
  const items: Array<{ scope_id: number; public_id: string }> = []
  let page = 0
  let hasNext = true
  while (hasNext) {
    const response = await api.get(`/api/v1/dashboard/scope-tabs?scopeType=ORGANIZATION&page=${page}`, { headers })
    expect(response.ok(), `organization fixture tabs: ${response.status()}`).toBeTruthy()
    const data = (await response.json()).data
    expect(Array.isArray(data.items)).toBe(true)
    expect(data.page).toBe(page)
    expect(typeof data.has_next).toBe('boolean')
    items.push(...data.items)
    hasNext = data.has_next
    page++
  }
  return items
}
async function openFeed(page: Page): Promise<void> {
  await page.goto(`/teams/${TEAM_SLUG}/announcements`, { waitUntil: 'domcontentloaded' })
  await expect(card(page, blog.announcementFeedId)).toBeVisible()
}
/** 実Dialogの前後Tabが全状態で外へ出ないことをDOMの結果で確認する。 */
async function assertDialogTrap(page: Page): Promise<void> {
  const dialog = page.getByRole('dialog')
  for (const key of ['Tab', 'Shift+Tab']) {
    for (let index = 0; index < 6; index++) {
      await page.keyboard.press(key)
      await expect.poll(() => dialog.evaluate(element => element.contains(document.activeElement))).toBe(true)
    }
  }
}
async function createBroadcast(channel: Fixture['channel'], title: string, body: string, scopeId = teamId, scopeType: Fixture['scopeType'] = 'TEAM'): Promise<Fixture> {
  const response = await api.post(`/api/v1/${scopeType === 'TEAM' ? 'teams' : 'organizations'}/${scopeId}/broadcast`, {
    headers: fixtureHeaders,
    data: { channel, targetRole: 'MEMBERS_AND_ABOVE', priority: 'NORMAL', content: { title, body } },
  })
  expect(response.status(), await response.text()).toBe(201)
  const data = { ...(await response.json()).data, channel, scopeId, scopeType } as Fixture
  expect(data.announcementFeedId).toBeGreaterThan(0)
  created.push(data)
  return data
}

async function createNormalBlog(scopeType: Fixture['scopeType'], scopeId: number): Promise<Fixture> {
  const post = await api.post('/api/v1/blog/posts', { headers: fixtureHeaders, data: {
    [scopeType === 'TEAM' ? 'teamId' : 'organizationId']: String(scopeId),
    title: `NormalBlog-${scopeType}-${stamp}`, slug: `normal-preview-${scopeType.toLowerCase()}-${stamp}`,
    body: `NormalBlogBody-${scopeType}-${stamp}`, postType: 'BLOG', visibility: 'MEMBERS_AND_ABOVE', crossPostToTimeline: false,
  } })
  expect(post.status(), await post.text()).toBe(201)
  const source = (await post.json()).data
  const fixture: Fixture = { announcementFeedId: 0, contentId: source.id, channel: 'BLOG_POST', scopeId, scopeType }
  created.push(fixture)
  expect(source.meta.postType).toBe('BLOG')
  const image = await api.post('/api/v1/blog/media/upload-url', { headers: fixtureHeaders, data: {
    media_type: 'IMAGE', content_type: 'image/png', file_size: PNG_BYTES.length,
    scope_type: scopeType, scope_id: scopeId, blog_post_id: source.id,
  } })
  expect(image.status(), `image fixture presign: ${image.status()}`).toBe(200)
  const media = (await image.json()).data
  const uploaded = await api.put(media.upload_url, { data: PNG_BYTES, headers: { 'Content-Type': 'image/png' } })
  expect([200, 204]).toContain(uploaded.status())
  const complete = await api.post(`/api/v1/blog/media/${media.media_id}/complete`, { headers: fixtureHeaders })
  expect(complete.status()).toBe(204)
  const updated = await api.put(`/api/v1/blog/posts/${source.id}`, { headers: fixtureHeaders, data: {
    title: source.content.title, body: `${source.content.body}\n\n${`![正規画像](${media.file_key})\n\n`.repeat(3)}`,
    visibility: 'MEMBERS_AND_ABOVE', version: source.audit.version,
  } })
  expect(updated.ok(), `image fixture body update: ${updated.status()}`).toBeTruthy()
  const published = await api.patch(`/api/v1/blog/posts/${source.id}/publish`, { headers: fixtureHeaders, data: { status: 'PUBLISHED' } })
  expect(published.ok(), `publish fixture: ${published.status()}`).toBeTruthy()
  const feed = await api.post(`/api/v1/${scopeType === 'TEAM' ? 'teams' : 'organizations'}/${scopeId}/announcements`, {
    headers: fixtureHeaders, data: { sourceType: 'BLOG_POST', sourceId: source.id },
  })
  expect(feed.status(), await feed.text()).toBe(201)
  fixture.announcementFeedId = (await feed.json()).data.id
  return fixture
}

test.describe('お知らせ本文プレビュー 実API', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ storageState: { cookies: [], origins: [] } })

  test.beforeAll(async () => {
    api = await playwrightRequest.newContext({ baseURL: API_BASE })
    const auth = await api.post('/api/v1/auth/login', { data: { email: ADMIN, password: PASSWORD } })
    expect(auth.ok(), `fixture login: ${auth.status()}`).toBeTruthy()
    fixtureHeaders = { Authorization: `Bearer ${(await auth.json()).data.accessToken}` }
    const team = await api.get(`/api/v1/teams/${TEAM_SLUG}`, { headers: fixtureHeaders })
    expect(team.ok(), `fixture team: ${team.status()}`).toBeTruthy()
    // TeamResponse.idはURL slug、Long scope APIにはnumericIdを使う。
    teamId = (await team.json()).data.numericId
    expect(Number.isSafeInteger(teamId)).toBe(true)
    expect(teamId).toBeGreaterThan(0)
    // seedの直属管理者を仮定せず、正規作成で今回専用ORGのownerADMINを得る。
    const previousOrganizations = await api.get(`/api/v1/teams/${TEAM_SLUG}/organizations`, { headers: fixtureHeaders })
    expect(previousOrganizations.ok()).toBeTruthy()
    previousTeamOrganizationSlugs = (await previousOrganizations.json()).data.map((item: { slug: string }) => item.slug)
    const org = await api.post('/api/v1/organizations', { headers: fixtureHeaders, data: {
      name: `PreviewOwnOrg-${stamp}`, slug: `preview-org-${stamp}`, orgType: 'COMMUNITY', visibility: 'PUBLIC',
    } })
    expect(org.status(), `organization fixture: ${org.status()}`).toBe(201)
    const organization = (await org.json()).data
    orgSlug = organization.slug
    ORG_ID = organization.numericId
    ownOrgCreated = true
    expect(Number.isSafeInteger(ORG_ID)).toBe(true)
    expect(ORG_ID).toBeGreaterThan(0)
    const affiliation = await api.post(`/api/v1/organizations/${orgSlug}/team-invites`, {
      headers: fixtureHeaders, data: { teamSlug: TEAM_SLUG },
    })
    expect(affiliation.status()).toBe(201)
    ownAffiliationId = (await affiliation.json()).data.id
    expect(Number.isSafeInteger(ownAffiliationId)).toBe(true)
    const accepted = await api.post(`/api/v1/teams/${TEAM_SLUG}/org-invites/${ownAffiliationId}/accept`, { headers: fixtureHeaders })
    expect(accepted.status()).toBe(200)
    expect((await accepted.json()).data.status).toBe('ACTIVE')
    const orgTeams = await api.get(`/api/v1/organizations/${orgSlug}/teams`, { headers: fixtureHeaders })
    expect(orgTeams.ok()).toBeTruthy()
    expect((await orgTeams.json()).data.some((item: { slug: string }) => item.slug === TEAM_SLUG)).toBe(true)
    const members = await api.get(`/api/v1/teams/${TEAM_SLUG}/members?size=200`, { headers: fixtureHeaders })
    expect(members.ok()).toBeTruthy()
    const teamMembers = (await members.json()).data
    expect(Array.isArray(teamMembers)).toBe(true)
    const memberAuth = await api.post('/api/v1/auth/login', { data: { email: MEMBER, password: PASSWORD } })
    expect(memberAuth.ok()).toBeTruthy()
    const memberHeaders = { Authorization: `Bearer ${(await memberAuth.json()).data.accessToken}` }
    const me = await api.get('/api/v1/users/me', { headers: memberHeaders })
    expect(me.ok()).toBeTruthy()
    const memberId = (await me.json()).data.id
    expect(Number.isSafeInteger(memberId)).toBe(true)
    expect(teamMembers.find((item: { userId: number; displayName: string }) => item.userId === memberId)?.displayName).toBeTruthy()
    const previousTabs = await organizationTabs(memberHeaders)
    expect(previousTabs.some(item => item.scope_id === ORG_ID), '既存所属を試験後に削除しないため未所属を確認').toBe(false)
    const orgMembers = await api.get(`/api/v1/organizations/${orgSlug}/members?size=200`, { headers: fixtureHeaders })
    expect(orgMembers.ok()).toBeTruthy()
    expect((await orgMembers.json()).data.some((item: { userId: number }) => item.userId === memberId)).toBe(false)
    const invite = await api.post(`/api/v1/organizations/${orgSlug}/invite-tokens`, {
      headers: fixtureHeaders, data: { roleId: 4, maxUses: 1, expiresIn: '1d' },
    })
    expect(invite.status()).toBe(201)
    const invitation = (await invite.json()).data
    ownOrgInviteId = invitation.id
    expect(Number.isSafeInteger(ownOrgInviteId)).toBe(true)
    const join = await api.post(`/api/v1/invite/${invitation.token}/join`, { headers: memberHeaders })
    expect(join.status()).toBe(200)
    ownOrgMemberUserId = memberId
    await test.info().attach('organization-membership-fixture', {
      body: JSON.stringify({ scopeId: ORG_ID, scopeSlug: orgSlug, affiliationId: ownAffiliationId, userId: memberId, inviteId: ownOrgInviteId, previouslyAffiliated: false }),
      contentType: 'application/json',
    })
    expect((await organizationTabs(memberHeaders)).find(item => item.scope_id === ORG_ID)?.public_id).toBe(orgSlug)
    // 本specは5件だけ作成し、broadcastのユーザー別レート制限を超えない。
    blog = await createBroadcast('BLOG_POST', blogTitle,
      `${marker}\n\n<script>window.__previewXss=1</script><a href="javascript:window.__previewXss=2">危険リンク</a><img src="invalid-preview-image" onerror="window.__previewXss=3"><iframe src="javascript:window.__previewXss=4"></iframe>\n\n[正規リンク](https://example.com/)\n\nhttps://example.com/${'long-url-'.repeat(60)}\n\n|列1|列2|\n|---|---|\n|${'長い表'.repeat(50)}|表の内容|\n\n${'長文を内部でスクロールします。\n\n'.repeat(100)}`)
    bulletin = await createBroadcast('BULLETIN_THREAD', bulletinTitle,
      `<p>${bulletinMarker}</p><script>window.__bulletinPreviewXss=1</script><img src="invalid-bulletin-image" onerror="window.__bulletinPreviewXss=2"><a href="javascript:window.__bulletinPreviewXss=3">危険リンク</a><iframe src="javascript:window.__bulletinPreviewXss=4"></iframe><a href="https://example.com/">掲示板の正規リンク</a>`)
    // CreateTeamRequestのslug制約（3〜30文字）を満たす固有fixture。
    otherTeamSlug = `preview-other-${stamp}`
    const other = await api.post('/api/v1/teams', { headers: fixtureHeaders, data: { name: `PreviewOther-${stamp}`, slug: otherTeamSlug } })
    expect(other.status(), await other.text()).toBe(201)
    const otherTeam = (await other.json()).data
    otherTeamSlug = otherTeam.slug
    expect(Number.isSafeInteger(otherTeam.numericId)).toBe(true)
    expect(otherTeam.numericId).toBeGreaterThan(0)
    otherBulletin = await createBroadcast('BULLETIN_THREAD', `PrivateThread-${stamp}`, `PrivateThreadBody-${stamp}`, otherTeam.numericId)
    const attachmentName = `preview-${stamp}.png`
    const presign = await api.post('/api/v1/bulletin/attachments/upload-url', { headers: fixtureHeaders, data: {
      targetType: 'THREAD', targetId: otherBulletin.contentId, fileName: attachmentName, contentType: 'image/png', fileSize: PNG_BYTES.length,
    } })
    expect(presign.status()).toBe(200)
    const upload = (await presign.json()).data
    const put = await api.put(upload.uploadUrl, { data: PNG_BYTES, headers: { 'Content-Type': 'image/png' } })
    expect([200, 204]).toContain(put.status())
    const attached = await api.post('/api/v1/bulletin/attachments', { headers: fixtureHeaders, data: {
      targetType: 'THREAD', targetId: otherBulletin.contentId, fileKey: upload.fileKey,
      originalFilename: attachmentName, contentType: 'image/png', fileSize: PNG_BYTES.length,
    } })
    expect(attached.status()).toBe(201)
    attachmentId = (await attached.json()).data.id
    orgBlog = await createBroadcast('BLOG_POST', `OrgBlog-${stamp}`, `OrgBlogBody-${stamp}`, ORG_ID, 'ORGANIZATION')
    orgBulletin = await createBroadcast('BULLETIN_THREAD', `OrgThread-${stamp}`, `OrgThreadBody-${stamp}`, ORG_ID, 'ORGANIZATION')
    normalBlogs.push(await createNormalBlog('TEAM', teamId), await createNormalBlog('ORGANIZATION', ORG_ID))
  })

  test.afterAll(async () => {
    if (!api) return
    // UI ADMINのセッション終了後にcleanup認証を更新し、token rotationとの競合を避ける。
    const auth = await api.post('/api/v1/auth/login', { data: { email: ADMIN, password: PASSWORD } })
    expect(auth.ok(), `cleanup login: ${auth.status()}`).toBeTruthy()
    fixtureHeaders = { Authorization: `Bearer ${(await auth.json()).data.accessToken}` }
    // 自作所属を先に解除し、後続コンテンツのcleanup失敗でも既存会員を変更しない。
    if (ownOrgMemberUserId) {
      // SYSTEM_ADMINにも直属ADMIN必須の除名APIを使わず、加入本人が自己退会する。
      const memberAuth = await api.post('/api/v1/auth/login', { data: { email: MEMBER, password: PASSWORD } })
      expect(memberAuth.ok()).toBeTruthy()
      const memberHeaders = { Authorization: `Bearer ${(await memberAuth.json()).data.accessToken}` }
      const me = await api.get('/api/v1/users/me', { headers: memberHeaders })
      expect(me.ok()).toBeTruthy()
      expect((await me.json()).data.id).toBe(ownOrgMemberUserId)
      const membership = await api.delete(`/api/v1/organizations/${orgSlug}/me`, { headers: memberHeaders })
      expect(membership.status()).toBe(204)
      const teamMembers = await api.get(`/api/v1/teams/${TEAM_SLUG}/members?size=200`, { headers: fixtureHeaders })
      expect(teamMembers.ok()).toBeTruthy()
      const existingTeamMembershipRetained = (await teamMembers.json()).data.some((item: { userId: number }) => item.userId === ownOrgMemberUserId)
      expect(existingTeamMembershipRetained).toBe(true)
      await test.info().attach('organization-membership-cleanup', {
        body: JSON.stringify({ scopeId: ORG_ID, userId: ownOrgMemberUserId, status: membership.status(), existingTeamMembershipRetained }),
        contentType: 'application/json',
      })
    }
    if (ownOrgInviteId) {
      const invite = await api.delete(`/api/v1/organizations/${orgSlug}/invite-tokens/${ownOrgInviteId}`, { headers: fixtureHeaders })
      expect(invite.status()).toBe(204)
    }
    if (attachmentId) {
      const attachment = await api.delete(`/api/v1/bulletin/attachments/${attachmentId}`, { headers: fixtureHeaders })
      expect([204, 404]).toContain(attachment.status())
    }
    for (const item of created.reverse()) {
      if (item.announcementFeedId) {
        const feed = await api.delete(`/api/v1/${item.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${item.scopeId}/announcements/${item.announcementFeedId}`, { headers: fixtureHeaders })
        expect([204, 404]).toContain(feed.status())
      }
      const path = item.channel === 'BLOG_POST' ? 'blog/posts' : 'bulletin/threads'
      const source = await api.delete(`/api/v1/${path}/${item.contentId}`, { headers: fixtureHeaders })
      expect([200, 204, 404]).toContain(source.status())
    }
    if (otherTeamSlug) {
      const team = await api.delete(`/api/v1/teams/${otherTeamSlug}`, { headers: fixtureHeaders })
      expect([200, 204, 404]).toContain(team.status())
    }
    if (ownOrgCreated) {
      // ACTIVE加盟の単独解除EPは未実装。ownORG正規削除で公開加盟から除外する。
      // raw履歴行は専用試験DBの破棄時まで保持し、seedの既存加盟は変更しない。
      const organization = await api.delete(`/api/v1/organizations/${orgSlug}`, { headers: fixtureHeaders })
      expect(organization.status()).toBe(204)
      const memberships = await api.get(`/api/v1/teams/${TEAM_SLUG}/organizations`, { headers: fixtureHeaders })
      expect(memberships.ok()).toBeTruthy()
      const currentSlugs = (await memberships.json()).data.map((item: { slug: string }) => item.slug)
      expect(currentSlugs).not.toContain(orgSlug)
      expect(currentSlugs).toEqual(expect.arrayContaining(previousTeamOrganizationSlugs))
      await test.info().attach('owned-organization-cleanup', {
        body: JSON.stringify({ scopeId: ORG_ID, affiliationId: ownAffiliationId, ownRemoved: true, existingAffiliationsRetained: true }),
        contentType: 'application/json',
      })
    }
    await api.dispose()
  })

  test('PREVIEW-01/11/13/14/15/16/17: 遷移なし・長文境界・閉じ位置/フォーカス・安全本文・遅延取得', async ({ page }, testInfo) => {
    test.setTimeout(150_000)
    await login(page)
    let gets = 0
    let reads = 0
    const imageUrls = new Set<string>()
    page.on('request', request => {
      if (request.url().endsWith(`/announcements/${blog.announcementFeedId}/preview`)) gets++
      if (request.url().endsWith(`/announcements/${blog.announcementFeedId}/read`) && request.method() === 'POST') reads++
    })
    await openFeed(page)
    expect(gets).toBe(0)
    for (const [width, height] of [[390, 844], [767, 844], [768, 844], [1280, 900], [844, 390]] as const) {
      await page.setViewportSize({ width, height })
      if (width === 1280) await page.locator('html').evaluate(element => element.classList.add('p-dark'))
      const trigger = card(page, blog.announcementFeedId)
      await trigger.scrollIntoViewIfNeeded()
      await trigger.focus()
      const position = await page.evaluate(() => window.scrollY)
      const url = page.url()
      await trigger.press('Enter')
      const dialog = page.getByRole('dialog', { name: blogTitle })
      await expect(dialog).toBeVisible()
      await expect(dialog).toContainText(marker)
      expect(page.url()).toBe(url)
      const rect = await dialog.boundingBox()
      expect(rect?.width).toBeLessThanOrEqual(width)
      expect(rect?.height).toBeLessThanOrEqual(height)
      expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      const close = dialog.getByTestId('announcement-preview-close')
      const original = dialog.getByTestId('announcement-preview-source')
      await expect(close).toBeInViewport()
      await expect(original).toBeInViewport()
      expect((await close.boundingBox())?.height).toBeGreaterThanOrEqual(44)
      expect(await dialog.locator('script, iframe, [onerror], [onclick], a[href^="javascript:"]').count()).toBe(0)
      expect(await page.evaluate(() => (window as Window & { __previewXss?: number }).__previewXss)).toBeUndefined()
      await expect(dialog.getByRole('link', { name: '正規リンク', exact: true })).toHaveAttribute('href', 'https://example.com/')
      for (const url of await dialog.locator('img').evaluateAll(elements => elements.map(element => (element as HTMLImageElement).src))) imageUrls.add(url)
      await close.focus()
      for (let tab = 0; tab < 10; tab++) {
        await page.keyboard.press('Tab')
        expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBe(true)
      }
      blogUrl = (await original.getAttribute('href')) ?? ''
      await page.screenshot({ path: testInfo.outputPath(`preview-${width}.png`) })
      await page.keyboard.press('Escape')
      await expect(dialog).not.toBeVisible()
      await expect(trigger).toBeFocused()
      expect(await page.evaluate(() => window.scrollY)).toBe(position)
    }
    await card(page, blog.announcementFeedId).press('Space')
    await expect(page.getByRole('dialog')).toContainText(marker)
    await page.mouse.click(1, 1)
    await expect(page.getByRole('dialog')).not.toBeVisible()
    await expect(card(page, blog.announcementFeedId)).toBeFocused()
    expect(gets).toBe(6)
    await expect.poll(() => reads).toBe(1)
    const persisted = await page.evaluate(async ({ bodyMarker, contentImageUrls }) => {
      const values = [...Object.values(localStorage), ...Object.values(sessionStorage)]
      let contentImageKeyStored = false
      for (const name of await caches.keys()) {
        const cache = await caches.open(name)
        for (const request of await cache.keys()) {
          if (contentImageUrls.includes(request.url)) contentImageKeyStored = true
          values.push(await (await cache.match(request))!.text())
        }
      }
      return { bodyStored: values.some(value => value.includes(bodyMarker)),
        contentImageStored: contentImageKeyStored || values.some(value => contentImageUrls.some(url => value.includes(url))) }
    }, { bodyMarker: marker, contentImageUrls: [...imageUrls] })
    expect(persisted.bodyStored).toBe(false)
    expect(persisted.contentImageStored).toBe(false)
    expect(blogUrl).toMatch(new RegExp(`^/blog/posts/[^/?]+\\?teamId=${teamId}$`))
  })

  test('PREVIEW-08: 気になれば元ブログへ明示遷移しscope queryを読む', async ({ page }) => {
    await login(page)
    await openFeed(page)
    await card(page, blog.announcementFeedId).click()
    const source = page.getByTestId('announcement-preview-source')
    await expect(source).toBeVisible()
    await source.click()
    await expect(page).toHaveURL(new RegExp(`/blog/posts/[^/?]+\\?teamId=${teamId}$`))
    await expect(page.locator('main')).toContainText(marker)
  })

  test('PREVIEW-18: 6言語でモーダルの閉じる/元ページを解決する', async ({ page, baseURL }) => {
    await login(page)
    for (const [locale, messages] of Object.entries({ ja, en, de, es, ko, zh })) {
      await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: baseURL ?? 'http://localhost:3011' }])
      await openFeed(page)
      await card(page, blog.announcementFeedId).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByTestId('announcement-preview-close')).toHaveText(messages.announcement.preview.close)
      await expect(dialog.getByTestId('announcement-preview-source')).toHaveText(messages.announcement.preview.open_source)
      expect(await dialog.textContent()).not.toContain('announcement.preview.')
      await dialog.getByTestId('announcement-preview-close').click()
      await expect(dialog).not.toBeVisible()
    }
  })

  test('PREVIEW-01/02/08: TEAM集約の組織feedは実所有scopeで開き、ORG切替後も同じ本文を表示する', async ({ page }) => {
    await login(page)
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await page.getByRole('tab', { name: 'チーム', exact: true }).click()
    const teamChip = page.getByTestId(`scope-tab-chip-TEAM-${TEAM_SLUG}`)
    await expect(teamChip).toBeVisible()
    if (await teamChip.getAttribute('aria-pressed') !== 'true') await teamChip.click()
    await expect(card(page, orgBlog.announcementFeedId)).toBeVisible()
    const requested: string[] = []
    page.on('request', request => { if (request.url().endsWith('/preview')) requested.push(new URL(request.url()).pathname) })
    await card(page, orgBlog.announcementFeedId).click()
    await expect(page.getByRole('dialog')).toContainText(`OrgBlogBody-${stamp}`)
    await expect(page.getByTestId('announcement-preview-source')).toHaveAttribute('href', new RegExp(`^/blog/posts/[^/?]+\\?organizationId=${ORG_ID}$`))
    expect(requested).toEqual([`/api/v1/organizations/${ORG_ID}/announcements/${orgBlog.announcementFeedId}/preview`])
    await page.getByTestId('announcement-preview-close').click()
    await page.getByRole('tab', { name: '組織', exact: true }).click()
    const orgChip = page.getByTestId(`scope-tab-chip-ORGANIZATION-${orgSlug}`)
    await expect(orgChip).toBeVisible()
    if (await orgChip.getAttribute('aria-pressed') !== 'true') await orgChip.click()
    await expect(card(page, orgBulletin.announcementFeedId)).toBeVisible()
    await card(page, orgBulletin.announcementFeedId).click()
    await expect(page.getByRole('dialog')).toContainText(`OrgThreadBody-${stamp}`)
    const source = page.getByTestId('announcement-preview-source')
    await expect(source).toHaveAttribute('href', `/organizations/${orgSlug}/bulletin?threadId=${orgBulletin.contentId}`)
    await source.click()
    await expect(page.locator('main')).toContainText(`OrgThreadBody-${stamp}`)
  })

  test('PREVIEW-01/08/15/16: TEAM/ORGの通常BLOG・署名画像も本文と正しいscope queryで開く', async ({ page }) => {
    await login(page)
    for (const fixture of normalBlogs) {
      if (fixture.scopeType === 'TEAM') await openFeed(page)
      else {
        await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
        await page.getByRole('tab', { name: '組織', exact: true }).click()
        const chip = page.getByTestId(`scope-tab-chip-ORGANIZATION-${orgSlug}`)
        await expect(chip).toBeVisible()
        if (await chip.getAttribute('aria-pressed') !== 'true') await chip.click()
      }
      // 本番用ビルドの既存PWAが実際に制御している状態で永続cacheを検証する。
      await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker?.controller)), { timeout: 30_000 }).toBe(true)
      await card(page, fixture.announcementFeedId).click()
      const body = `NormalBlogBody-${fixture.scopeType}-${stamp}`
      await expect(page.getByRole('dialog')).toContainText(body)
      const images = page.getByRole('dialog').getByRole('img', { name: '正規画像' })
      await expect(images).toHaveCount(3)
      for (const image of await images.all()) await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
      const urls = await images.evaluateAll(elements => elements.map(element => (element as HTMLImageElement).src))
      const stored = await page.evaluate(async ({ bodyMarker, imageUrls }) => {
        const values = [...Object.values(localStorage), ...Object.values(sessionStorage)]
        let keyStored = false
        for (const name of await caches.keys()) {
          const cache = await caches.open(name)
          for (const request of await cache.keys()) {
            if (imageUrls.includes(request.url)) keyStored = true
            values.push(await (await cache.match(request))!.text())
          }
        }
        return { body: values.some(value => value.includes(bodyMarker)),
          image: keyStored || values.some(value => imageUrls.some(url => value.includes(url))) }
      }, { bodyMarker: body, imageUrls: urls })
      expect(stored).toEqual({ body: false, image: false })
      const source = page.getByTestId('announcement-preview-source')
      await expect(source).toHaveAttribute('href', new RegExp(`^/blog/posts/[^/?]+\\?${fixture.scopeType === 'TEAM' ? 'teamId' : 'organizationId'}=${fixture.scopeId}$`))
      await source.click()
      await expect(page.locator('main')).toContainText(body)
    }
  })

  test('PREVIEW-15/16: 閲覧可能な添付を開き、所属解除後の同じボタンで最新ACLを再評価する', async ({ page }) => {
    const memberLogin = await api.post('/api/v1/auth/login', { data: { email: MEMBER, password: PASSWORD } })
    expect(memberLogin.ok()).toBeTruthy()
    const memberHeaders = { Authorization: `Bearer ${(await memberLogin.json()).data.accessToken}` }
    const me = await api.get('/api/v1/users/me', { headers: memberHeaders })
    expect(me.ok()).toBeTruthy()
    const memberId = (await me.json()).data.id
    const invite = await api.post(`/api/v1/teams/${otherTeamSlug}/invite-tokens`, { headers: fixtureHeaders,
      data: { roleId: Number(process.env.E2E_MEMBER_ROLE_ID ?? '4'), expiresIn: '1d', maxUses: 1 } })
    expect(invite.status()).toBe(201)
    const joined = await api.post(`/api/v1/invite/${(await invite.json()).data.token}/join`, { headers: memberHeaders })
    expect(joined.ok()).toBeTruthy()
    let removed = false
    try {
      await login(page)
      await page.goto(`/teams/${otherTeamSlug}/announcements`, { waitUntil: 'domcontentloaded' })
      await card(page, otherBulletin.announcementFeedId).click()
      const dialog = page.getByRole('dialog')
      const button = dialog.getByText(`preview-${stamp}.png`, { exact: true }).locator('..').getByRole('button')
      await expect(button).toBeVisible()
      const statuses: number[] = []
      page.on('response', response => { if (new URL(response.url()).pathname === `/api/v1/bulletin/attachments/${attachmentId}/download-url`) statuses.push(response.status()) })
      const opened = page.context().waitForEvent('page')
      await button.click()
      const content = await opened
      await content.waitForLoadState('domcontentloaded')
      await content.close()
      await expect.poll(() => statuses).toEqual([200])
      const revoked = await api.delete(`/api/v1/teams/${otherTeamSlug}/members/${memberId}`, { headers: fixtureHeaders })
      expect(revoked.status()).toBe(204)
      removed = true
      await button.click()
      await expect.poll(() => statuses.length).toBe(2)
      expect([403, 404]).toContain(statuses[1])
      await expect(dialog).toBeVisible()
    }
    finally {
      if (!removed) {
        const revoke = await api.delete(`/api/v1/teams/${otherTeamSlug}/members/${memberId}`, { headers: fixtureHeaders })
        expect([204, 404]).toContain(revoke.status())
      }
    }
  })

  test('PREVIEW-01/08/15: 掲示板HTMLを安全に表示しthreadIdの深いリンクで同じスレッドを開く', async ({ page }) => {
    await login(page)
    await openFeed(page)
    await card(page, bulletin.announcementFeedId).click()
    const dialog = page.getByRole('dialog', { name: bulletinTitle })
    await expect(dialog).toContainText(bulletinMarker)
    expect(await dialog.locator('script, iframe, [onerror], a[href^="javascript:"]').count()).toBe(0)
    expect(await page.evaluate(() => (window as Window & { __bulletinPreviewXss?: number }).__bulletinPreviewXss)).toBeUndefined()
    await expect(dialog.getByRole('link', { name: '掲示板の正規リンク', exact: true })).toHaveAttribute('href', 'https://example.com/')
    const source = dialog.getByTestId('announcement-preview-source')
    bulletinUrl = (await source.getAttribute('href')) ?? ''
    expect(bulletinUrl).toBe(`/teams/${TEAM_SLUG}/bulletin?threadId=${bulletin.contentId}`)
    await source.click()
    await expect(page).toHaveURL(bulletinUrl)
    await expect(page.locator('main')).toContainText(bulletinMarker)
  })

  test('PREVIEW-09: 実ネットワーク断の取得失敗から手動再試行できる', async ({ page }) => {
    await login(page)
    await openFeed(page)
    await page.context().setOffline(true)
    try {
      await card(page, blog.announcementFeedId).click()
      await expect(page.getByRole('dialog').getByTestId('load-error-state')).toBeVisible()
      await expect(page.getByTestId('announcement-preview-source')).not.toBeVisible()
      await assertDialogTrap(page)
      await page.keyboard.press('Escape')
      await expect(card(page, blog.announcementFeedId)).toBeFocused()
      await card(page, blog.announcementFeedId).press('Space')
      await expect(page.getByRole('dialog').getByTestId('load-error-state')).toBeVisible()
    }
    finally { await page.context().setOffline(false) }
    await page.getByRole('dialog').getByTestId('load-error-state').getByRole('button').click()
    await expect(page.getByRole('dialog')).toContainText(marker)
  })

  test('PREVIEW-05/15: ブログのSUPPORTER拒否と掲示板の所属許可を元URLで再評価する', async ({ page }) => {
    await login(page, SUPPORTER)
    await page.goto(blogUrl, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('main')).not.toContainText(marker)
    await expect(page.getByText('記事がありません', { exact: true })).toBeVisible()
    // 掲示板元内容はSCOPE_AFFILIATED。feedのMEMBERS_AND_ABOVEとは別の既存認可。
    await page.goto(bulletinUrl, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('main')).toContainText(bulletinMarker)
  })

  test('PREVIEW-05/15: 非所属ユーザーの掲示板元URL直打ちで本文を表示しない', async ({ page }) => {
    await login(page)
    await page.goto(`/teams/${otherTeamSlug}/bulletin?threadId=${otherBulletin.contentId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('load-error-state')).toBeVisible()
    await expect(page.locator('main')).not.toContainText(`PrivateThreadBody-${stamp}`)
  })

  test('PREVIEW-05/15: 別チームのthreadId直打ちは所属管理者でも本文なし', async ({ page }) => {
    await login(page, ADMIN)
    await page.goto(`/teams/${otherTeamSlug}/bulletin?threadId=${bulletin.contentId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('load-error-state')).toBeVisible()
    await expect(page.locator('main')).not.toContainText(bulletinMarker)
  })

  test('PREVIEW-10/13/17: 実通信の取得中もTabを閉じ込め、閉じた旧応答を表示しない', async ({ page }) => {
    await login(page)
    await openFeed(page)
    const trigger = card(page, blog.announcementFeedId)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Network.enable')
    try {
      await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 8_000, downloadThroughput: -1, uploadThroughput: -1 })
      await trigger.press('Enter')
      await expect(page.getByTestId('announcement-preview')).toHaveAttribute('aria-busy', 'true')
      await assertDialogTrap(page)
      await page.keyboard.press('Escape')
      await expect(trigger).toBeFocused()
      await expect(page.getByRole('dialog')).not.toBeVisible()
      await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })
      await trigger.press('Space')
      await expect(page.getByRole('dialog')).toContainText(marker)
      await page.getByTestId('announcement-preview-close').focus()
      await page.keyboard.press('Enter')
      await expect(trigger).toBeFocused()
    }
    finally { await cdp.detach() }
  })

  test('PREVIEW-06/07/13: own期別会費のLOCKEDから管理者の現金入金UI後に新しい本文を取得する', async ({ page, browser }) => {
    test.setTimeout(120_000)
    const fixture = normalBlogs.find(item => item.scopeType === 'TEAM')!
    const itemName = `PreviewFee-${stamp}`
    const memberLogin = await api.post('/api/v1/auth/login', { data: { email: MEMBER, password: PASSWORD } })
    expect(memberLogin.ok()).toBeTruthy()
    const me = await api.get('/api/v1/users/me', { headers: { Authorization: `Bearer ${(await memberLogin.json()).data.accessToken}` } })
    expect(me.ok()).toBeTruthy()
    const memberId = (await me.json()).data.id as number
    const adminLogin = await api.post('/api/v1/auth/login', { data: { email: ADMIN, password: PASSWORD } })
    expect(adminLogin.ok()).toBeTruthy()
    fixtureHeaders = { Authorization: `Bearer ${(await adminLogin.json()).data.accessToken}` }
    const members = await api.get(`/api/v1/teams/${TEAM_SLUG}/members?size=200`, { headers: fixtureHeaders })
    expect(members.ok()).toBeTruthy()
    const member = (await members.json()).data.find((item: { userId: number }) => item.userId === memberId)
    expect(member?.displayName, '本人を表示名で選択するためのfixture').toBeTruthy()
    const item = await api.post(`/api/v1/teams/${teamId}/payment-items`, { headers: fixtureHeaders, data: { name: itemName, type: 'TERM', amount: 5000, termEndsOn: '2027-12-31' } })
    expect(item.status()).toBe(201)
    const itemId = (await item.json()).data.id as number
    const gatePath = `/api/v1/teams/${teamId}/content-payment-gates`
    const gates = [{ contentType: 'ANNOUNCEMENT', contentId: fixture.announcementFeedId }, { contentType: 'POST', contentId: fixture.contentId }]
    let paymentId: number | undefined
    const adminContext = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://127.0.0.1:3011' })
    try {
      for (const gate of gates) {
        const response = await api.put(gatePath, { headers: fixtureHeaders, data: { ...gate, gates: [{ paymentItemId: itemId, isTitleHidden: false }] } })
        expect(response.ok()).toBeTruthy()
      }
      await login(page)
      await openFeed(page)
      const trigger = card(page, fixture.announcementFeedId)
      await trigger.press('Enter')
      await expect(page.getByTestId('announcement-preview-locked')).toBeVisible()
      await expect(page.getByRole('dialog')).not.toContainText(`NormalBlogBody-TEAM-${stamp}`)
      await expect(page.getByTestId('announcement-preview-source')).not.toBeVisible()
      await assertDialogTrap(page)
      await page.keyboard.press('Escape')
      await expect(trigger).toBeFocused()
      // フィードがFULLでも、元ブログだけのPOSTゲートを迂回しない。
      const feedUnlocked = await api.put(gatePath, { headers: fixtureHeaders, data: { ...gates[0], gates: [] } })
      expect(feedUnlocked.ok()).toBeTruthy()
      await trigger.press('Space')
      await expect(page.getByTestId('announcement-preview-locked')).toBeVisible()
      await expect(page.getByRole('dialog')).not.toContainText(`NormalBlogBody-TEAM-${stamp}`)
      await expect(page.getByTestId('announcement-preview-source')).not.toBeVisible()
      await page.getByTestId('announcement-preview-close').focus()
      await page.keyboard.press('Enter')
      await expect(trigger).toBeFocused()
      const feedRelocked = await api.put(gatePath, { headers: fixtureHeaders, data: { ...gates[0], gates: [{ paymentItemId: itemId, isTitleHidden: false }] } })
      expect(feedRelocked.ok()).toBeTruthy()
      const adminPage = await adminContext.newPage()
      await login(adminPage, ADMIN)
      await adminPage.goto(`/teams/${TEAM_SLUG}/payments`, { waitUntil: 'domcontentloaded' })
      await adminPage.locator('.w-64 button').filter({ hasText: itemName }).click()
      await adminPage.getByTestId('payment-record-open').click()
      await adminPage.getByTestId('payment-record-member').click()
      await adminPage.getByRole('option', { name: member.displayName, exact: true }).click()
      await expect(adminPage.getByTestId('payment-record-method')).toContainText(/現金|CASH/)
      const recorded = adminPage.waitForResponse(response => new URL(response.url()).pathname === `/api/v1/teams/${teamId}/payment-items/${itemId}/payments` && response.request().method() === 'POST')
      await adminPage.getByTestId('payment-record-submit').click()
      const payment = await recorded
      expect(payment.ok()).toBeTruthy()
      const paid = (await payment.json()).data
      paymentId = paid.id
      expect(paid.statusInfo.status).toBe('PAID')
      expect(payment.request().postDataJSON()).toMatchObject({ userId: memberId, paymentMethod: 'CASH' })
      const fresh = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/announcements/${fixture.announcementFeedId}/preview`))
      await trigger.press('Enter')
      expect((await fresh).status()).toBe(200)
      await expect(page.getByRole('dialog')).toContainText(`NormalBlogBody-TEAM-${stamp}`)
      await expect(page.getByTestId('announcement-preview-source')).toBeVisible()
    }
    finally {
      await adminContext.close()
      const cleanup = await api.post('/api/v1/auth/login', { data: { email: ADMIN, password: PASSWORD } })
      expect(cleanup.ok()).toBeTruthy()
      fixtureHeaders = { Authorization: `Bearer ${(await cleanup.json()).data.accessToken}` }
      for (const gate of gates) {
        const cleared = await api.put(gatePath, { headers: fixtureHeaders, data: { ...gate, gates: [] } })
        expect(cleared.ok()).toBeTruthy()
      }
      if (paymentId) {
        const canceled = await api.delete(`/api/v1/teams/${teamId}/payment-items/${itemId}/payments/${paymentId}`, { headers: fixtureHeaders })
        expect([200, 204]).toContain(canceled.status())
      }
      const removed = await api.delete(`/api/v1/teams/${teamId}/payment-items/${itemId}`, { headers: fixtureHeaders })
      expect([200, 204]).toContain(removed.status())
    }
  })

  test('PREVIEW-16: 実署名の自然失効後に画像の再取得で回復する', async ({ page }) => {
    test.setTimeout(750_000)
    await login(page)
    await openFeed(page)
    const fixture = normalBlogs.find(item => item.scopeType === 'TEAM')!
    await expect(card(page, fixture.announcementFeedId)).toBeVisible()
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Debugger.enable')
    await cdp.send('Network.enable')
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
    const previewPath = `/announcements/${fixture.announcementFeedId}/preview`
    const response = page.waitForResponse(result => new URL(result.url()).pathname.endsWith(previewPath))
    // 実APIの通信を遅らせてからJSを停止し、本文描画前に署名の自然失効を待つ。
    // 応答・時計・画像URLを差し替えず、MinIOが実際に拒否することを確認する。
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false, latency: 3000, downloadThroughput: -1, uploadThroughput: -1,
    })
    let paused = false
    try {
      await card(page, fixture.announcementFeedId).click()
      const stopped = new Promise<void>(resolve => cdp.once('Debugger.paused', () => resolve()))
      await cdp.send('Debugger.pause')
      await stopped
      paused = true
      await cdp.send('Network.emulateNetworkConditions', {
        offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
      })
      const result = await response
      expect(result.status()).toBe(200)
      const payload = (await result.json()).data
      const markdown = payload.blogPost?.content?.body as string
      const signed = markdown.match(/https?:\/\/[^\s)]+/g)?.find(value => value.includes('X-Amz-Signature='))
      expect(Boolean(signed), '本文の実署名URLが必要').toBe(true)
      const url = new URL(signed!)
      const date = url.searchParams.get('X-Amz-Date') ?? ''
      const ttl = Number(url.searchParams.get('X-Amz-Expires'))
      expect(/^\d{8}T\d{6}Z$/.test(date), '署名日時の形式').toBe(true)
      expect(ttl).toBe(600)
      const issued = Date.UTC(Number(date.slice(0, 4)), Number(date.slice(4, 6)) - 1,
        Number(date.slice(6, 8)), Number(date.slice(9, 11)), Number(date.slice(11, 13)), Number(date.slice(13, 15)))
      await new Promise(resolve => setTimeout(resolve, Math.max(0, issued + ttl * 1000 + 2000 - Date.now())))
      const expiredImage = page.waitForResponse(image => image.url() === signed, { timeout: 30_000 })
      await cdp.send('Debugger.resume')
      paused = false
      const rejected = await expiredImage
      expect(rejected.status()).toBe(403)
      expect((await rejected.text()).toLowerCase().includes('expired'), 'ストレージによる署名失効拒否').toBe(true)
      const dialog = page.getByRole('dialog')
      const status = dialog.getByRole('status')
      await expect(status).toContainText(ja.announcement.preview.image_failed)
      await status.getByRole('button', { name: ja.announcement.preview.retry, exact: true }).click()
      const images = dialog.getByRole('img', { name: '正規画像', exact: true })
      await expect(images).toHaveCount(3)
      for (const image of await images.all()) {
        await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
      }
      await expect(status).not.toBeVisible()
    }
    finally {
      if (paused) await cdp.send('Debugger.resume')
      await cdp.send('Network.emulateNetworkConditions', {
        offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
      })
      await cdp.detach()
    }
  })
})
