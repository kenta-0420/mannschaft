import { expect, test, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/** 投稿と返信は実UI、前提の独立チーム作成・認証・後始末だけ実APIを使う。 */
test.use({ storageState: { cookies: [], origins: [] } })
const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const password = process.env.TEST_PASSWORD ?? 'TestPass2026!'
const ownerEmail = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const memberEmail = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const outsiderEmail = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'

async function login(page: Page, email: string) {
  await loginViaApi(page, { email, password }, { apiBaseUrl: API })
  const response = await page.request.get(`${API}/api/v1/users/me`)
  expect(response.status()).toBe(200)
  const profile = (await response.json()).data as { id: number; systemRole: string | null }
  expect(profile.systemRole, 'SYSTEM_ADMINの迂回で認可試験を通さない').toBeNull()
  return profile
}
async function createTeam(page: Page, slug: string) {
  const response = await page.request.post(`${API}/api/v1/teams`, { data: { name: slug, slug, visibility: 'PUBLIC' } })
  expect(response.status(), '前提チームを実APIで作成').toBe(201)
  const data = (await response.json()).data as { numericId: number; slug: string }
  expect(Number.isSafeInteger(data.numericId)).toBe(true)
  return { id: data.numericId, slug: data.slug }
}

test('掲示板の投稿本文・返信・再読込・4幅とスコープ拒否', async ({ page, browser }, testInfo) => {
  test.setTimeout(240_000)
  const prefix = `board-${Date.now()}`
  const threads: number[] = []
  let joinedTeamSlug: string | null = null
  const previewResourceRequests: string[] = []
  page.on('request', request => {
    if (request.url().includes(`preview-resource-${prefix}`)) previewResourceRequests.push(request.url())
  })
  const teams: Array<{ id: number; slug: string; page: Page }> = []
  const otherContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'ja-JP', storageState: { cookies: [], origins: [] } })
  const other = await otherContext.newPage()
  const ownerContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'ja-JP', storageState: { cookies: [], origins: [] } })
  const owner = await ownerContext.newPage()
  const anonymousContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'ja-JP', storageState: { cookies: [], origins: [] } })
  try {
    await login(owner, ownerEmail)
    const team = await createTeam(owner, prefix)
    teams.push({ ...team, page: owner })
    const member = await login(page, memberEmail)
    const invitePath = `${API}/api/v1/teams/${team.slug}/invite-tokens`
    const invitationResponse = await owner.request.post(invitePath, {
      data: { roleId: Number(process.env.E2E_MEMBER_ROLE_ID ?? '4'), maxUses: 1, expiresIn: '1d' },
    })
    expect(invitationResponse.status()).toBe(201)
    const invitation = (await invitationResponse.json()).data
    const joined = await page.request.post(`${API}/api/v1/invite/${invitation.token}/join`)
    expect(joined.status()).toBe(200)
    joinedTeamSlug = team.slug
    const revoke = await owner.request.delete(`${invitePath}/${invitation.id}`)
    expect([200, 204]).toContain(revoke.status())
    const permission = await page.request.get(`${API}/api/v1/teams/${team.slug}/me/permissions`)
    expect(permission.status()).toBe(200)
    expect((await permission.json()).data.roleName).toBe('MEMBER')
    await page.goto(`/teams/${team.slug}/bulletin`)
    await waitForHydration(page)
    for (let index = 0; index < 4; index++) {
      await page.getByRole('button', { name: '新規スレッド', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '新規スレッド' })
      const longUrl = index === 3 ? ` https://example.com/${'long'.repeat(24)}` : ''
      await dialog.getByPlaceholder('スレッドのタイトル').fill(`${prefix}-${index}${longUrl}`)
      await dialog.getByPlaceholder('本文を入力...').fill(`投稿本文${index}\n二行目${longUrl} ${'あ'.repeat(101)}<img src="/icons/icon-192x192.png?preview-resource-${prefix}">`)
      const saved = page.waitForResponse(response => response.request().method() === 'POST' && /\/bulletin\/threads(?:\?|$)/.test(response.url()))
      await dialog.getByRole('button', { name: '投稿', exact: true }).click()
      const response = await saved
      expect(response.status()).toBe(201)
      threads.push((await response.json()).data.id)
      await expect(dialog).toBeHidden()
      await expect(page.getByTestId('bulletin-thread-preview').filter({ hasText: `投稿本文${index}` })).toBeVisible()
    }
    await page.reload()
    await waitForHydration(page)
    await expect(page.getByTestId('bulletin-thread-card')).toHaveCount(4)
    for (const [width, columns] of [[390, 1], [768, 2], [1280, 3], [1600, 4]]) {
      await page.setViewportSize({ width: width!, height: 1000 })
      await expect.poll(() => page.getByTestId('bulletin-thread-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(columns)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `${width}pxで横パンなし`).toBe(true)
      const box = await page.getByTestId('bulletin-thread-select').first().boundingBox()
      expect(box!.width).toBeGreaterThanOrEqual(44)
      expect(box!.height).toBeGreaterThanOrEqual(44)
      await page.screenshot({ path: testInfo.outputPath(`board-${width}.png`), fullPage: true })
    }
    expect(previewResourceRequests, '一覧プレビューで本文画像の追加通信なし').toEqual([])
    await page.getByTestId('bulletin-thread-select').filter({ hasText: `${prefix}-0` }).click()
    await expect(page.locator('.article-body')).toContainText('投稿本文0\n二行目')
    const replyBody = `返信本文-${prefix}`
    await page.getByPlaceholder('返信を入力...').fill(replyBody)
    const replied = page.waitForResponse(response => response.request().method() === 'POST' && /\/replies$/.test(response.url()))
    await page.getByRole('button', { name: '返信', exact: true }).click()
    const replyResponse = await replied
    expect(replyResponse.status()).toBe(201)
    const replyAuthor = (await replyResponse.json()).data.author as { id: number; displayName: string }
    expect(replyAuthor.id).toBe(member.id)
    expect(replyAuthor.displayName).toBeTruthy()
    expect(replyAuthor.displayName).not.toBe('不明なユーザー')
    await expect(page.getByTestId('bulletin-reply').filter({ hasText: replyBody })).toContainText(replyAuthor.displayName)
    await page.goto(`/teams/${team.slug}/bulletin?threadId=${threads[0]}`)
    await waitForHydration(page)
    await expect(page.locator('.article-body')).toContainText('投稿本文0')
    await expect(page.getByTestId('bulletin-reply').filter({ hasText: replyBody })).toContainText(replyAuthor.displayName)

    await login(other, outsiderEmail)
    for (const hasOwnTeam of [false, true]) {
      if (hasOwnTeam) {
        const foreignTeam = await createTeam(other, `${prefix}-other`)
        teams.push({ ...foreignTeam, page: other })
      }
      await other.goto(`/teams/${team.slug}/bulletin?threadId=${threads[0]}`)
      await waitForHydration(other)
      const errorState = other.getByTestId('load-error-state')
      await expect(errorState).toBeVisible()
      await expect(errorState).toContainText(/この画面をご利用いただけません|お探しの情報を表示できませんでした/)
      await testInfo.attach(`scope-denied-${hasOwnTeam ? 'other-team' : 'outsider'}`, {
        body: await errorState.innerText(), contentType: 'text/plain',
      })
      await expect(other.getByRole('button', { name: '新規スレッド', exact: true })).toHaveCount(0)
      await expect(other.locator('.article-body')).toHaveCount(0)
      await expect(other.getByTestId('bulletin-thread-card')).toHaveCount(0)
      const denied = await other.request.get(`${API}/api/v1/teams/${team.id}/bulletin/threads/${threads[0]}`)
      expect([403, 404]).toContain(denied.status())
      expect(await denied.text()).not.toContain('投稿本文0')
    }
    const anonymous = await anonymousContext.newPage()
    await anonymous.goto(`/teams/${team.slug}/bulletin?threadId=${threads[0]}`)
    await expect(anonymous).toHaveURL(/\/login(?:\?|$)/)
    await expect(anonymous.locator('.article-body')).toHaveCount(0)
  } finally {
    for (const id of threads) {
      const response = await page.request.delete(`${API}/api/v1/bulletin/threads/${id}`)
      expect([200, 204]).toContain(response.status())
    }
    if (joinedTeamSlug) {
      const left = await page.request.delete(`${API}/api/v1/teams/${joinedTeamSlug}/me`)
      expect([200, 204]).toContain(left.status())
    }
    for (const team of [...teams].reverse()) {
      const response = await team.page.request.delete(`${API}/api/v1/teams/${team.slug}`)
      expect([200, 204]).toContain(response.status())
    }
    await otherContext.close()
    await ownerContext.close()
    await anonymousContext.close()
  }
})

/** CSSクラスではなく、ブラウザーの文字行とヒット領域を実測する。 */
test('詳細の操作ラベルと投稿者情報を読みやすく保ち主要操作を44pxにする', async ({ page }, testInfo) => {
  const title = `detail-touch-${Date.now()}`
  let team: { id: number; slug: string } | undefined
  let threadId: number | undefined
  await login(page, ownerEmail)
  try {
    team = await createTeam(page, title)
    await page.goto(`/teams/${team.slug}/bulletin`)
    await waitForHydration(page)
    await page.getByRole('button', { name: 'あとで決める', exact: true }).click()
    await page.getByRole('button', { name: '新規スレッド', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新規スレッド' })
    await dialog.getByPlaceholder('スレッドのタイトル').fill(title)
    await dialog.getByPlaceholder('本文を入力...').fill('操作と投稿者表示の検証')
    const created = page.waitForResponse(response => response.request().method() === 'POST' && /\/bulletin\/threads(?:\?|$)/.test(response.url()))
    await dialog.getByRole('button', { name: '投稿', exact: true }).click()
    const response = await created
    expect(response.status()).toBe(201)
    threadId = (await response.json()).data.id
    await expect(dialog).toBeHidden()
    await page.getByTestId('bulletin-thread-select').filter({ hasText: title }).click()
    const heading = page.getByRole('heading', { name: title, level: 2, exact: true })
    await expect(heading).toBeVisible()
    for (const width of [360, 390, 1280]) {
      await page.setViewportSize({ width, height: 1000 })
      const metrics = await heading.evaluate((element) => {
        function textLines(target: Element) {
          const tops = new Set<number>()
          const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT)
          let node: Node | null
          while ((node = walker.nextNode())) {
            if (!node.textContent?.trim()) continue
            const range = document.createRange()
            range.selectNodeContents(node)
            for (const rect of range.getClientRects()) if (rect.width > 0) tops.add(Math.round(rect.top))
          }
          return tops.size
        }
        const detail = element.parentElement!.parentElement!
        const labels = ['一覧へ戻る', 'ピン留め', 'ロック', '削除', '返信']
        const buttons = Array.from(detail.querySelectorAll('button')).filter(button => labels.includes(button.textContent!.trim())).map((button) => {
          const rect = button.getBoundingClientRect()
          const label = button.querySelector('.p-button-label')!
          return { text: label.textContent!.trim(), lines: textLines(label), width: rect.width, height: rect.height, left: rect.left, right: rect.right }
        })
        const metadata = Array.from(element.nextElementSibling!.children).filter(item => item.tagName === 'SPAN' && !item.classList.contains('p-avatar')).map(item => ({ text: item.textContent!.trim(), lines: textLines(item) }))
        return { buttons, metadata }
      })
      await writeFile(testInfo.outputPath(`detail-${width}.json`), JSON.stringify(metrics, null, 2), 'utf8')
      await page.screenshot({ path: testInfo.outputPath(`detail-${width}.png`), fullPage: true })
      expect.soft(metrics.buttons, `${width}pxの主要操作`).toHaveLength(5)
      for (const button of metrics.buttons) {
        expect.soft(button.lines, `${width}px ${button.text}の行数`).toBe(1)
        expect.soft(button.width, `${width}px ${button.text}の幅`).toBeGreaterThanOrEqual(44)
        expect.soft(button.height, `${width}px ${button.text}の高さ`).toBeGreaterThanOrEqual(44)
        expect.soft(button.left, `${width}px ${button.text}の左端`).toBeGreaterThanOrEqual(0)
        expect.soft(button.right, `${width}px ${button.text}の右端`).toBeLessThanOrEqual(width)
      }
      expect.soft(metrics.metadata).toHaveLength(3)
      for (const item of metrics.metadata) expect.soft(item.lines, `${width}px ${item.text}の行数`).toBe(1)
    }
    await page.getByPlaceholder('返信を入力...').fill('操作検証の返信')
    const replied = page.waitForResponse(response => response.request().method() === 'POST' && /\/replies$/.test(response.url()))
    await page.getByRole('button', { name: '返信', exact: true }).click()
    expect((await replied).status()).toBe(201)
    await expect(page.getByTestId('bulletin-reply').filter({ hasText: '操作検証の返信' })).toBeVisible()
  } finally {
    if (threadId) expect([200, 204]).toContain((await page.request.delete(`${API}/api/v1/bulletin/threads/${threadId}`)).status())
    if (team) expect([200, 204]).toContain((await page.request.delete(`${API}/api/v1/teams/${team.slug}`)).status())
  }
})
