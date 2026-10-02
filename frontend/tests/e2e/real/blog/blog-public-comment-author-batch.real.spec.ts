import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const OWNER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: PASSWORD,
}

interface Team {
  numericId: number
  slug: string
}

interface Post {
  id: number
}

interface Comment {
  commentId: string
  authorId: number
  authorDisplayName: string
  content: string
}

interface CommentPage {
  content: Comment[]
  totalElements: number
}

async function openUser(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(page, OWNER, { apiBaseUrl: API_BASE })
  return { context, page }
}

test('WAVE7-REAL-001: 匿名訪問者が公開チームの導線で同一著者のコメントを読める', async ({
  browser,
}) => {
  test.setTimeout(300_000)

  const owner = await openUser(browser)
  const anonymousContext = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const anonymous = await anonymousContext.newPage()
  owner.page.setDefaultNavigationTimeout(30_000)
  anonymous.setDefaultNavigationTimeout(30_000)

  const suffix = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`
  const title = `wave7-public-comment-${suffix}`
  const marker = `wave7-public-comment-body-${suffix}`
  const commentContents = [`wave7-comment-first-${suffix}`, `wave7-comment-second-${suffix}`]
  let team: Team | null = null
  let post: Post | null = null
  const commentIds: string[] = []
  const cleanupErrors: string[] = []

  try {
    const createTeam = await owner.page.request.post(`${API_BASE}/api/v1/teams`, {
      data: {
        name: `w7-${suffix}`,
        visibility: 'PUBLIC',
      },
    })
    expect(createTeam.status(), `create disposable PUBLIC team: ${await createTeam.text()}`).toBe(
      201,
    )
    team = ((await createTeam.json()) as { data: Team }).data

    const createPost = await owner.page.request.post(`${API_BASE}/api/v1/blog/posts`, {
      data: {
        teamId: String(team.numericId),
        title,
        body: marker,
        visibility: 'PUBLIC',
        postType: 'BLOG',
      },
    })
    expect(createPost.status(), 'create disposable public post').toBe(201)
    post = ((await createPost.json()) as { data: Post }).data

    const publish = await owner.page.request.patch(
      `${API_BASE}/api/v1/blog/posts/${post.id}/publish`,
      {
        data: { status: 'PUBLISHED' },
      },
    )
    expect(publish.status(), 'publish disposable post').toBe(200)

    for (const content of commentContents) {
      const createComment = await owner.page.request.post(
        `${API_BASE}/api/v1/public/blog-posts/${post.id}/comments`,
        { data: { content } },
      )
      expect(createComment.status(), `create comment ${content}`).toBe(201)
      const comment = (await createComment.json()) as Comment
      expect(comment.commentId).toBeTruthy()
      commentIds.push(comment.commentId)
    }

    const publicComments = await anonymous.request.get(
      `${API_BASE}/api/v1/public/blog-posts/${post.id}/comments?page=0&size=20`,
    )
    expect(publicComments.status(), 'anonymous comment API').toBe(200)
    const commentPage = (await publicComments.json()) as CommentPage
    const createdComments = commentPage.content.filter((comment) =>
      commentIds.includes(comment.commentId),
    )
    expect(createdComments, 'created comments returned from API').toHaveLength(2)
    expect(
      commentPage.totalElements,
      'comment count includes both created comments',
    ).toBeGreaterThanOrEqual(2)
    expect(
      new Set(createdComments.map((comment) => comment.authorId)).size,
      'same author created both comments',
    ).toBe(1)
    expect(
      new Set(createdComments.map((comment) => comment.authorDisplayName)).size,
      'same author name returned',
    ).toBe(1)
    expect(createdComments[0]?.authorDisplayName, 'author display name is present').toBeTruthy()

    const pageErrors: string[] = []
    anonymous.on('pageerror', (error) => pageErrors.push(error.message))

    await anonymous.goto(`/public/teams/${team.slug}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(anonymous)
    const postLink = anonymous
      .getByTestId('public-post-card')
      .filter({ hasText: title })
      .getByRole('link')
    await expect(postLink, 'public team page contains article entry').toBeVisible()
    await postLink.click()
    await anonymous.waitForURL(new RegExp(`/public/teams/${team.slug}/posts/${post.id}$`))
    await waitForHydration(anonymous)

    await expect(anonymous.getByTestId('public-post-body')).toContainText(marker)
    const commentSection = anonymous.getByTestId('comment-section')
    await expect(commentSection).toContainText('(2)')
    for (const content of commentContents) {
      await expect(commentSection.getByText(content, { exact: true })).toBeVisible()
    }
    await expect(
      commentSection.getByText(createdComments[0]!.authorDisplayName, { exact: true }),
    ).toHaveCount(2)
    expect(pageErrors, '公開ページの未捕捉例外').toEqual([])

    const hide = await owner.page.request.patch(
      `${API_BASE}/api/v1/blog/posts/${post.id}/public-visible`,
      {
        data: { publicVisible: false },
      },
    )
    expect(hide.status(), 'hide disposable post').toBe(204)

    const hiddenComments = await anonymous.request.get(
      `${API_BASE}/api/v1/public/blog-posts/${post.id}/comments?page=0&size=20`,
    )
    expect(hiddenComments.status(), 'hidden post comment API').toBe(404)
  } finally {
    if (post) {
      for (const commentId of commentIds) {
        const result = await owner.page.request
          .delete(`${API_BASE}/api/v1/public/blog-posts/${post.id}/comments/${commentId}`)
          .catch((error: unknown) => {
            cleanupErrors.push(`comment ${commentId} delete failed: ${String(error)}`)
            return null
          })
        if (result && result.status() !== 204) {
          cleanupErrors.push(`comment ${commentId} delete returned ${result.status()}`)
        }
      }

      const result = await owner.page.request
        .delete(`${API_BASE}/api/v1/blog/posts/${post.id}`)
        .catch((error: unknown) => {
          cleanupErrors.push(`post delete failed: ${String(error)}`)
          return null
        })
      if (result && result.status() !== 204)
        cleanupErrors.push(`post delete returned ${result.status()}`)
    }
    if (team) {
      const result = await owner.page.request
        .delete(`${API_BASE}/api/v1/teams/${team.slug}`)
        .catch((error: unknown) => {
          cleanupErrors.push(`team delete failed: ${String(error)}`)
          return null
        })
      if (result && result.status() !== 204)
        cleanupErrors.push(`team delete returned ${result.status()}`)
    }

    await Promise.all([owner.context.close(), anonymousContext.close()])
    expect(cleanupErrors, `cleanup failed:\n${cleanupErrors.join('\n')}`).toEqual([])
  }
})
