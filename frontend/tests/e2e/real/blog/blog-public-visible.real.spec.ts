import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const OWNER = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: PASSWORD }
const OUTSIDER = {
  email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.TEST_OUTSIDER_PASSWORD ?? PASSWORD,
}
const LABEL = {
  toggle: '\u6295\u7a3f\u306e\u516c\u958b\u30fb\u975e\u516c\u958b\u3092\u5207\u308a\u66ff\u3048\u308b',
  visible: '\u516c\u958b\u4e2d',
  hidden: '\u975e\u516c\u958b',
} as const

interface Team { numericId: number, slug: string }
interface Post { id: number }

async function openUser(
  browser: Browser,
  credentials: { email: string, password: string },
): Promise<{ context: BrowserContext, page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE })
  return { context, page }
}

test('WAVE5-REAL-001: UI visibility toggle controls public pages and rejects outsider edit', async ({ browser }) => {
  test.setTimeout(300_000)
  const owner = await openUser(browser, OWNER)
  const outsider = await openUser(browser, OUTSIDER)
  const anonymousContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const anonymous = await anonymousContext.newPage()
  for (const page of [owner.page, outsider.page, anonymous]) {
    page.setDefaultNavigationTimeout(30_000)
  }
  const suffix = Date.now()
  const title = `wave5-public-visible-${suffix}`
  const marker = `wave5-public-visible-body-${suffix}`
  let team: Team | null = null
  let post: Post | null = null
  const cleanupErrors: string[] = []

  try {
    const createTeam = await owner.page.request.post(`${API_BASE}/api/v1/teams`, {
      data: {
        name: `w5-${suffix}-${crypto.randomUUID().slice(0, 8)}`,
        visibility: 'PUBLIC',
      },
    })
    expect(
      createTeam.status(),
      `create disposable PUBLIC team: ${await createTeam.text()}`,
    ).toBe(201)
    team = ((await createTeam.json()) as { data: Team }).data
    expect(team.numericId).toBeGreaterThan(0)
    expect(team.slug).toBeTruthy()

    const createPost = await owner.page.request.post(`${API_BASE}/api/v1/blog/posts`, {
      data: {
        teamId: String(team.numericId), title, body: marker,
        visibility: 'PUBLIC', postType: 'BLOG',
      },
    })
    expect(createPost.status(), 'create disposable post').toBe(201)
    post = ((await createPost.json()) as { data: Post }).data
    expect(post.id).toBeGreaterThan(0)
    const publish = await owner.page.request.patch(`${API_BASE}/api/v1/blog/posts/${post.id}/publish`, {
      data: { status: 'PUBLISHED' },
    })
    expect(publish.status(), 'publish disposable post').toBe(200)

    const editPath = `/blog/posts/${post.id}/edit`
    await owner.page.goto(editPath, { waitUntil: 'domcontentloaded' })
    await waitForHydration(owner.page)
    const toggle = owner.page.getByRole('switch', { name: LABEL.toggle })
    await expect(toggle).toBeVisible()
    await expect(toggle).toBeChecked()
    await expect(owner.page.getByText(LABEL.visible, { exact: true }).last()).toBeVisible()

    const hideCall = owner.page.waitForResponse((r) =>
      r.request().method() === 'PATCH'
      && new URL(r.url()).pathname === `/api/v1/blog/posts/${post!.id}/public-visible`)
    await toggle.click()
    expect((await hideCall).status(), 'UI hide request').toBe(204)
    await expect(toggle).not.toBeChecked()
    await owner.page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(owner.page)
    await expect(toggle, 'hidden state survives reload').not.toBeChecked()
    await expect(owner.page.getByText(LABEL.hidden, { exact: true })).toBeVisible()

    await anonymous.goto(`/public/teams/${team.slug}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(anonymous)
    await expect(anonymous.getByText(title, { exact: true }), 'hidden post absent from list').toHaveCount(0)
    const hiddenDetail = await anonymous.goto(`/public/teams/${team.numericId}/posts/${post.id}`, {
      waitUntil: 'domcontentloaded',
    })
    expect(hiddenDetail?.status(), 'hidden public detail').toBe(404)
    await expect(anonymous.getByText(title, { exact: true })).toHaveCount(0)
    await expect(anonymous.getByText(marker, { exact: true })).toHaveCount(0)

    const outsiderLoad = outsider.page.waitForResponse((r) =>
      r.request().method() === 'GET'
      && new URL(r.url()).pathname === `/api/v1/users/me/blog/posts/${post!.id}`)
    await outsider.page.goto(editPath, { waitUntil: 'domcontentloaded' })
    expect((await outsiderLoad).status(), 'outsider edit load conceals existence').toBe(404)
    await expect(outsider.page.locator(`input[value="${title}"]`), 'title not disclosed').toHaveCount(0)
    await expect(outsider.page.getByText(marker, { exact: true }), 'body not disclosed').toHaveCount(0)
    await expect(outsider.page.getByTestId('blog-load-error')).toContainText('記事を読み込めませんでした')
    await expect(outsider.page.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
    await expect(outsider.page.getByRole('button', { name: '今すぐ公開', exact: true })).toHaveCount(0)
    await expect(outsider.page.locator('#autosave-toggle')).toHaveCount(0)
    await expect(outsider.page.getByRole('switch', { name: LABEL.toggle })).toHaveCount(0)

    await owner.page.goto(editPath, { waitUntil: 'domcontentloaded' })
    await waitForHydration(owner.page)
    await expect(toggle).not.toBeChecked()
    const showCall = owner.page.waitForResponse((r) =>
      r.request().method() === 'PATCH'
      && new URL(r.url()).pathname === `/api/v1/blog/posts/${post!.id}/public-visible`)
    await toggle.click()
    expect((await showCall).status(), 'UI show request').toBe(204)
    await owner.page.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(owner.page)
    await expect(toggle, 'visible state survives reload').toBeChecked()
    await expect(owner.page.getByText(LABEL.visible, { exact: true }).last()).toBeVisible()

    await anonymous.goto(`/public/teams/${team.slug}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(anonymous)
    await expect(anonymous.getByText(title, { exact: true }), 'visible post appears in list').toBeVisible()
    const visibleDetail = await anonymous.goto(`/public/teams/${team.numericId}/posts/${post.id}`, {
      waitUntil: 'domcontentloaded',
    })
    expect(visibleDetail?.status(), 'visible public detail').toBe(200)
    await expect(anonymous.getByRole('heading', { name: title, level: 1 })).toBeVisible()
    await expect(anonymous.getByTestId('public-post-body')).toContainText(marker)
  } finally {
    if (post) {
      const result = await owner.page.request.delete(`${API_BASE}/api/v1/users/me/blog/posts/${post.id}`)
        .catch((error: unknown) => { cleanupErrors.push(`post delete failed: ${String(error)}`); return null })
      if (result && result.status() !== 204) cleanupErrors.push(`post delete returned ${result.status()}`)
      if (team) {
        const probe = await anonymous.request.get(
          `${API_BASE}/api/v1/public/teams/${team.numericId}/posts/${post.id}`,
        ).catch(() => null)
        if (probe && probe.status() !== 404) cleanupErrors.push(`deleted post returned ${probe.status()}`)
      }
    }
    if (team) {
      const result = await owner.page.request.delete(`${API_BASE}/api/v1/teams/${team.slug}`)
        .catch((error: unknown) => { cleanupErrors.push(`team delete failed: ${String(error)}`); return null })
      if (result && result.status() !== 204) cleanupErrors.push(`team delete returned ${result.status()}`)
    }
    await Promise.all([owner.context.close(), outsider.context.close(), anonymousContext.close()])
    expect(cleanupErrors, `cleanup failed:\n${cleanupErrors.join('\n')}`).toEqual([])
  }
})
